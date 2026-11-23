package com.linkeddesk.service;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.DeskRules;
import com.linkeddesk.analysis.Feedback;
import com.linkeddesk.analysis.Lexicon;
import com.linkeddesk.analysis.Thresholds;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.Logs;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedFetcher;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.ReadState;
import com.linkeddesk.store.TopicHits;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Coordinates downloading, storing, searching and analysing. Used by both the window and the headless
 * daily task. Only one download runs at a time across all processes (the window and the Windows task).
 */
public final class NewsService {
    /** Thrown when another download (usually the scheduled task) is already running. */
    public static final class BusyException extends IOException {
        BusyException() { super("A download is already running (probably the daily Windows task). It will show here when it finishes."); }
    }

    private final ArticleStore store;
    private final Settings settings;
    private final FeedHealth health;
    private final TopicHits topicHits;
    private final FeedFetcher fetcher = new FeedFetcher();
    private final ReentrantLock inProcess = new ReentrantLock();

    public NewsService(ArticleStore store, Settings settings) {
        this(store, settings, new FeedHealth(), new TopicHits());
    }

    public NewsService(ArticleStore store, Settings settings, FeedHealth health, TopicHits topicHits) {
        this.store = store;
        this.settings = settings;
        this.health = health.load();
        this.topicHits = topicHits;
    }

    public ArticleStore store() { return store; }
    public Settings settings() { return settings; }
    public FeedHealth health() { return health; }
    public TopicHits topicHits() { return topicHits; }

    private ReadState reads;
    private com.linkeddesk.analysis.Regions regions;
    private long regionsStamp = Long.MIN_VALUE;

    /** Where outlets are based (regions.txt, re-read when edited). */
    public synchronized com.linkeddesk.analysis.Regions regions() {
        java.nio.file.Path f = AppPaths.regionsFile();
        long st;
        try { st = java.nio.file.Files.exists(f) ? java.nio.file.Files.getLastModifiedTime(f).toMillis() + java.nio.file.Files.size(f) : -1; }
        catch (IOException e) { st = -1; }
        if (regions == null || st != regionsStamp) {
            try { regions = com.linkeddesk.analysis.Regions.load(f); }
            catch (IOException e) { Logs.error("Reading regions.txt", e); regions = com.linkeddesk.analysis.Regions.defaults(); }
            try { regionsStamp = java.nio.file.Files.getLastModifiedTime(f).toMillis() + java.nio.file.Files.size(f); } catch (IOException e) { regionsStamp = -1; }
        }
        return regions;
    }

    /** What you've read and starred (data/read.txt, data/starred.tsv). */
    public synchronized ReadState reads() {
        if (reads == null) reads = new ReadState(AppPaths.dataDir());
        return reads.load();
    }

    /**
     * Your starred stories, as they stand now (including coverage that joined them after you starred them),
     * newest first.
     */
    public Analyzer.Result analyzeSaved() throws IOException {
        java.util.Set<String> starred = reads().starredArticleIds();
        if (starred.isEmpty()) return new Analyzer.Result(List.of(), List.of(), 0);
        List<Article> found = new ArrayList<>();
        store.scan(a -> starred.contains(a.id), found::add);
        if (found.isEmpty()) return new Analyzer.Result(List.of(), List.of(), 0);
        long min = found.stream().mapToLong(a -> a.published).min().getAsLong();
        store.ensureLoaded(min - 3 * 86_400_000L);
        java.util.TreeSet<Long> days = new java.util.TreeSet<>();
        for (Article a : found) days.add(a.published / 86_400_000L);
        List<Article> near = new ArrayList<>();
        for (Article a : store.since(min - 3 * 86_400_000L)) {
            long d = a.published / 86_400_000L;
            Long k = days.ceiling(d - 5);
            if (k != null && k <= d + 5) near.add(a);
        }
        Analyzer.Result r = Analyzer.analyze(near, options());
        List<StoryCluster> kept = new ArrayList<>();
        for (StoryCluster c : r.stories()) if (c.articles.stream().anyMatch(a -> starred.contains(a.id))) kept.add(c);
        kept.sort(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed());
        return new Analyzer.Result(kept, List.of(), r.articleCount(), List.of(), List.of());
    }

    public void loadAll() throws IOException {
        store.load();
        topicHits.load();
    }

    /** Daily download: every regular feed and every active topic; paused feeds are skipped. */
    public FetchReport fetchNow(Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        List<FeedSource> feeds = new ArrayList<>(FeedCatalog.load());
        for (Topic t : TopicCatalog.load()) if (!t.paused()) feeds.addAll(t.feeds());
        return download(feeds, false, "download", progress, cancelled, null);
    }

    /** "Test all feeds": tries every feed now, paused ones included. */
    public FetchReport testAllFeeds(Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        List<FeedSource> feeds = new ArrayList<>(FeedCatalog.load());
        for (Topic t : TopicCatalog.load()) feeds.addAll(t.feeds());
        return download(feeds, true, "test all feeds", progress, cancelled, null);
    }

    /** Downloads just the given topic (used right after a topic is added, and by live search). */
    public FetchReport fetchTopic(Topic t, Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        return download(t.feeds(), true, "topic: " + t.name(), progress, cancelled, null);
    }

    private FetchReport download(List<FeedSource> feeds, boolean includePaused, String who,
                                 Consumer<String> progress, BooleanSupplier cancelled,
                                 Consumer<List<String>> foundIds) throws IOException {
        if (!inProcess.tryLock()) throw new BusyException();
        try (FileChannel ch = FileChannel.open(AppPaths.home().resolve(".download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = ch.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new BusyException();
            try {
                return runDownload(feeds, includePaused, who, progress, cancelled, foundIds);
            } finally {
                lock.release();
            }
        } finally {
            inProcess.unlock();
        }
    }

    private FetchReport runDownload(List<FeedSource> feeds, boolean includePaused, String who,
                                    Consumer<String> progress, BooleanSupplier cancelled,
                                    Consumer<List<String>> foundIds) throws IOException {
        long t0 = System.nanoTime();
        long now = System.currentTimeMillis();
        FetchReport report = new FetchReport();
        store.reloadIfChanged();
        List<FeedSource> due = new ArrayList<>();
        for (FeedSource f : feeds) {
            if (includePaused || health.isDue(f, now)) due.add(f);
            else report.add(new FetchReport.FeedResult(f, 0, 0, null, false, true, 0));
        }
        report.expected = feeds.size();
        int[] done = {0};
        fetcher.fetchAll(due, health, fetched -> {
            FeedFetcher.FeedItems fi = fetched;
            synchronized (report) {
                int added = 0;
                if (fi.ok() && fi.feed().filter() != null) {          // apply -excluded words (Bing ignores them)
                    SearchQuery f = new SearchQuery(fi.feed().filter());
                    List<Article> kept = fi.items().stream().filter(a -> !f.excluded(a)).toList();
                    fi = new FeedFetcher.FeedItems(fi.feed(), kept, fi.error(), fi.notModified(), fi.etag(), fi.lastModified(), fi.attempts());
                }
                try {
                    if (fi.ok()) {
                        List<Article> newOnes = store.addAll(fi.items());
                        added = newOnes.size();
                        report.addedArticles.addAll(newOnes);
                        if (foundIds != null) foundIds.accept(fi.items().stream().map(store::canonicalId).toList());
                        if (fi.feed().isTopic()) {
                            List<String> canon = fi.items().stream().map(store::canonicalId).toList();
                            topicHits.add(fi.feed().topic(), canon, System.currentTimeMillis());
                        }
                        health.recordSuccess(fi.feed(), added, fi.notModified(), fi.etag(), fi.lastModified(), System.currentTimeMillis());
                    } else if (!"Cancelled".equals(fi.error())) {
                        health.recordFailure(fi.feed(), fi.error(), System.currentTimeMillis());
                    }
                } catch (IOException e) {
                    Logs.error("Saving articles from " + fi.feed().name(), e);
                }
                report.totalAdded += added;
                report.add(new FetchReport.FeedResult(fi.feed(), fi.items().size(), added, fi.error(), fi.notModified(), false, fi.attempts()));
                done[0]++;
                if (progress != null) progress.accept("Downloaded " + done[0] + " of " + due.size() + " feeds (" + report.totalAdded + " new articles)");
            }
        }, cancelled);
        report.cancelled = cancelled.getAsBoolean();
        report.finished = java.time.Instant.now();
        try { health.save(); } catch (IOException e) { Logs.error("Saving feed health", e); }
        if (!report.cancelled && who.equals("download")) {
            settings.reload();
            settings.setLastFetch(System.currentTimeMillis());
            settings.save();
        }
        Logs.run(who, report.summary(), report.detail(), (System.nanoTime() - t0) / 1e9);
        return report;
    }

    /** Analyses articles published in the last {@code days} days (0 = all history), leaving out muted outlets. */
    public Analyzer.Result analyze(int days) {
        List<Article> articles = days <= 0 ? store.all()
                : store.since(System.currentTimeMillis() - days * 24L * 3600_000);
        return tieTickers(Analyzer.analyze(sources().visible(articles), options()));
    }

    // ------------------------------------------------------------------ P5: watchlist, sources, tickers

    private com.linkeddesk.alerts.Sources sources;
    private com.linkeddesk.alerts.Watches watches;
    private com.linkeddesk.alerts.Tickers tickers;

    /** Outlets you muted or weighted (sources.txt, re-read when it changes). */
    public synchronized com.linkeddesk.alerts.Sources sources() {
        if (sources == null) sources = new com.linkeddesk.alerts.Sources(AppPaths.home());
        return sources.load();
    }

    /** Your watchlist (watchlist.txt), with aliases from aliases.txt. */
    public synchronized com.linkeddesk.alerts.Watches watches() {
        if (watches == null) watches = new com.linkeddesk.alerts.Watches(AppPaths.home());
        return watches.load(options().lexicon());
    }

    /** The tickers you follow (tickers.txt). */
    public synchronized com.linkeddesk.alerts.Tickers tickers() {
        if (tickers == null) tickers = new com.linkeddesk.alerts.Tickers(AppPaths.home());
        return tickers.load();
    }

    /** Stories about a company you follow also show in Finance (ALR-F05). */
    private Analyzer.Result tieTickers(Analyzer.Result r) {
        var t = tickers();
        if (t.isEmpty()) return r;
        for (StoryCluster c : r.stories()) {
            if (c.category == Category.FINANCE || c.also != null) continue;
            if (!t.of(c).isEmpty()) c.also = Category.FINANCE;
        }
        return r;
    }

    /** Stories about a watch over the last {@code days} days (from the archive), newest first. */
    public Analyzer.Result watchStories(com.linkeddesk.alerts.Watches.Watch w, int days) throws IOException {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        store.ensureLoaded(since);
        var ws = watches();
        List<Article> hits = new ArrayList<>();
        for (Article a : sources().visible(store.since(since))) if (ws.matching(a).stream().anyMatch(x -> x.name().equals(w.name()))) hits.add(a);
        Analyzer.Result r = Analyzer.analyze(hits, options());
        r.stories().sort(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed());
        return r;
    }

    /** Stories about a ticker over the last {@code days} days, newest first. */
    public Analyzer.Result tickerStories(String symbol, int days) throws IOException {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        store.ensureLoaded(since);
        var t = tickers();
        List<Article> hits = new ArrayList<>();
        for (Article a : sources().visible(store.since(since))) if (t.of(a).contains(symbol)) hits.add(a);
        Analyzer.Result r = Analyzer.analyze(hits, options());
        r.stories().sort(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed());
        return r;
    }

    /** The daily briefing: the last 24 hours, analysed with four days of context (for storylines and links). */
    public com.linkeddesk.analysis.Briefing briefing() throws IOException {
        long now = System.currentTimeMillis();
        store.ensureLoaded(now - 4 * 86_400_000L);
        var src = sources();
        return com.linkeddesk.analysis.Briefing.build(tieTickers(Analyzer.analyze(src.visible(store.since(now - 4 * 86_400_000L)), options())), now, src::coverage);
    }

    /** Stories made only of articles a topic found. */
    public Analyzer.Result analyzeTopic(String topic, int days) {
        long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 24L * 3600_000;
        var ids = topicHits.articles(topic);
        List<Article> list = new ArrayList<>();
        for (Article a : store.since(since)) if (ids.contains(a.id)) list.add(a);
        return Analyzer.analyze(sources().visible(list), options());
    }

    // ------------------------------------------------------------------ search

    /** The field filters of a search (source:, desk:, after:, region:, is:…) as one test. */
    public java.util.function.Predicate<Article> fieldTest(SearchQuery q) {
        if (q.fields().isEmpty()) return a -> true;
        ReadState rs = reads();
        java.util.Set<String> starred = rs.starredArticleIds();
        com.linkeddesk.analysis.Regions reg = regions();
        return a -> q.fieldsMatch(a, reg::of, rs::isRead, x -> starred.contains(x.id));
    }

    /** Search filters. Any may be null/0 for "any". */
    public record SearchFilter(long from, long to, Category desk, String source) {
        public static final SearchFilter ANY = new SearchFilter(0, 0, null, null);
        boolean accepts(Article a) {
            if (from > 0 && a.published < from) return false;
            if (to > 0 && a.published > to) return false;
            if (desk != null && a.category() != desk && a.feedCategory != desk) return false;
            return source == null || source.isBlank() || a.source.toLowerCase().contains(source.toLowerCase());
        }
    }

    public record SearchResult(List<Article> matches, Analyzer.Result stories, int total, boolean capped, long millis) {}

    public static final int SEARCH_CAP = 1500;

    /** Searches every saved article (all history). Results are grouped into stories, newest first. */
    public SearchResult search(SearchQuery q, SearchFilter filter) throws IOException {
        long t0 = System.nanoTime();
        // Plain words, no filters: the index counts the matches and only the newest are read.
        if (filter.desk() == null && (filter.source() == null || filter.source().isBlank()) && !sources().any()) {
            var fast = store.index().newest(q, SEARCH_CAP, filter.from(), filter.to());
            if (fast != null) {
                Analyzer.Result stories = Analyzer.analyze(fast.newest(), options());
                return new SearchResult(fast.newest(), stories, fast.total(), fast.total() > SEARCH_CAP, (System.nanoTime() - t0) / 1_000_000);
            }
        }
        List<Article> hits = new ArrayList<>();
        if (!q.isEmpty()) {
            java.util.function.Predicate<Article> fields = fieldTest(q);
            store.find(q, a -> filter.accepts(a) && (!q.hasWords() || q.matches(a)) && fields.test(a), hits::add);
        }
        if (q.fields().stream().noneMatch(f -> f.name().equals("source"))) hits = new ArrayList<>(sources().visible(hits));  // muted outlets only on request
        hits.sort(Comparator.comparingLong((Article a) -> a.published).reversed());
        int total = hits.size();
        boolean capped = total > SEARCH_CAP;
        List<Article> kept = capped ? new ArrayList<>(hits.subList(0, SEARCH_CAP)) : hits;
        Analyzer.Result stories = Analyzer.analyze(kept, options());
        return new SearchResult(kept, stories, total, capped, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Live search: downloads fresh news for the words now (Google News + Bing News), saves it, and returns
     * archive matches plus everything the live search found (even when the words only appear in the full
     * article, not in the headline or summary we keep).
     */
    public SearchResult liveSearch(SearchQuery q, SearchFilter filter, Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        if (!q.hasWords()) throw new IOException("Live search needs some words to look for (field filters like source: only apply to saved news).");
        Topic temp = new Topic(filter.desk() == null ? Category.WORLD : filter.desk(), "Live search: " + q.wordsText(), q.wordsText(), "en-US", false);
        List<FeedSource> feeds = temp.feeds().stream()
                .map(f -> new FeedSource(f.category(), f.name(), f.url(), null, f.filter())).toList();  // not a saved topic
        java.util.Set<String> found = java.util.concurrent.ConcurrentHashMap.newKeySet();
        download(feeds, true, "live search: " + q.text(), progress, cancelled, found::addAll);
        long t0 = System.nanoTime();
        List<Article> hits = new ArrayList<>();
        java.util.function.Predicate<Article> fields = fieldTest(q);
        store.scan(a -> filter.accepts(a) && (found.contains(a.id) || q.matches(a)) && fields.test(a), hits::add);
        hits.sort(Comparator.comparingLong((Article a) -> a.published).reversed());
        int total = hits.size();
        boolean capped = total > SEARCH_CAP;
        List<Article> kept = capped ? new ArrayList<>(hits.subList(0, SEARCH_CAP)) : hits;
        return new SearchResult(kept, Analyzer.analyze(kept, options()), total, capped, (System.nanoTime() - t0) / 1_000_000);
    }

    // ------------------------------------------------------------------ linking settings and your corrections

    private Analyzer.Options options;
    private long optionsStamp = Long.MIN_VALUE;

    private static java.nio.file.Path[] tuningFiles() {
        return new java.nio.file.Path[]{AppPaths.aliasesFile(), AppPaths.stopwordsFile(), AppPaths.feedbackFile(),
                AppPaths.deskRulesFile(), AppPaths.calibrationFile()};
    }

    private static long stamp() {
        long s = 17;
        for (java.nio.file.Path p : tuningFiles()) {
            try { s = s * 31 + java.nio.file.Files.getLastModifiedTime(p).toMillis() * 7 + java.nio.file.Files.size(p); }
            catch (IOException e) { s = s * 31 + 1; }
        }
        return s;
    }

    /**
     * Aliases, stop words, your link verdicts, your desk rules and the calibrated thresholds, read from the
     * files next to feeds.txt (re-read whenever one of them changes, also when edited by hand).
     */
    public synchronized Analyzer.Options options() {
        long st = stamp();
        if (options != null && st == optionsStamp) return options;
        Lexicon lex;
        try { lex = Lexicon.load(AppPaths.aliasesFile(), AppPaths.stopwordsFile()); }
        catch (IOException e) { Logs.error("Reading aliases.txt / stopwords.txt", e); lex = Lexicon.defaults(); }
        for (String p : lex.problems()) Logs.error(p, null);
        Thresholds t;
        try { t = Thresholds.load(AppPaths.calibrationFile()); }
        catch (IOException e) { Logs.error("Reading calibration.properties", e); t = Thresholds.defaults(); }
        Feedback fb;
        try { fb = Feedback.load(AppPaths.feedbackFile()); }
        catch (IOException e) { Logs.error("Reading link-feedback.tsv", e); fb = new Feedback(); }
        DeskRules dr;
        try { dr = DeskRules.load(AppPaths.deskRulesFile()); }
        catch (IOException e) { Logs.error("Reading desk-rules.tsv", e); dr = new DeskRules(); }
        options = new Analyzer.Options(lex, t, fb, dr);
        optionsStamp = stamp();
        return options;
    }

    private synchronized void changed() { options = null; }

    /** Marks the link between two stories right or wrong (replacing an earlier verdict on the same pair). */
    public synchronized void judgeLink(StoryCluster from, StoryCluster to, boolean right, List<String> terms) throws IOException {
        Feedback fb = Feedback.load(AppPaths.feedbackFile());
        fb.add(new Feedback.Entry(right ? Feedback.Kind.RIGHT : Feedback.Kind.WRONG, System.currentTimeMillis(),
                Feedback.ids(from), Feedback.ids(to), terms, from.headline, to.headline));
        fb.save(AppPaths.feedbackFile());
        changed();
    }

    /** Takes an article out of a story it was wrongly grouped into. */
    public synchronized void splitArticle(Article a, StoryCluster from) throws IOException {
        java.util.Set<String> others = new java.util.LinkedHashSet<>();
        for (Article x : from.articles) if (!x.id.equals(a.id)) others.add(x.id);
        if (others.isEmpty()) return;
        Feedback fb = Feedback.load(AppPaths.feedbackFile());
        fb.add(new Feedback.Entry(Feedback.Kind.SPLIT, System.currentTimeMillis(), java.util.Set.of(a.id), others, List.of(), a.title, from.headline));
        fb.save(AppPaths.feedbackFile());
        changed();
    }

    public synchronized void removeVerdict(Feedback.Entry e) throws IOException {
        Feedback fb = Feedback.load(AppPaths.feedbackFile());
        fb.entries().stream().filter(x -> x.key().equals(e.key())).findFirst().ifPresent(fb::remove);
        fb.save(AppPaths.feedbackFile());
        changed();
    }

    public synchronized void addDeskRule(DeskRules.Rule r) throws IOException {
        DeskRules d = DeskRules.load(AppPaths.deskRulesFile());
        d.add(r);
        d.save(AppPaths.deskRulesFile());
        changed();
    }

    public synchronized void removeDeskRule(DeskRules.Rule r) throws IOException {
        DeskRules d = DeskRules.load(AppPaths.deskRulesFile());
        d.rules().stream().filter(x -> x.equals(r)).findFirst().ifPresent(d::remove);
        d.save(AppPaths.deskRulesFile());
        changed();
    }

    public synchronized void addStopWords(List<String> words) throws IOException {
        Lexicon.addStopWords(AppPaths.stopwordsFile(), words);
        changed();
    }

    public synchronized void saveThresholds(Thresholds t) throws IOException {
        t.save(AppPaths.calibrationFile());
        changed();
    }

    /** The articles around the ones you gave verdicts on (2 days either side), for scoring your verdicts. */
    public List<Article> verdictArchive() {
        Feedback fb = options().feedback();
        if (fb.isEmpty()) return List.of();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Feedback.Entry e : fb.entries()) { ids.addAll(e.a()); ids.addAll(e.b()); }
        List<Article> all = store.all();
        java.util.TreeSet<Long> days = new java.util.TreeSet<>();
        for (Article a : all) if (ids.contains(a.id)) days.add(a.published / 86_400_000L);
        List<Article> out = new ArrayList<>();
        for (Article a : all) {
            long d = a.published / 86_400_000L;
            Long near = days.ceiling(d - 2);
            if (near != null && near <= d + 2) out.add(a);
        }
        return out.size() > 20_000 ? new ArrayList<>(out.subList(out.size() - 20_000, out.size())) : out;
    }

    /** Scores of the current (or given) thresholds on the labelled sample. */
    public Calibrator.Metrics sampleMetrics(Thresholds t) throws IOException {
        Analyzer.Options o = options();
        return Calibrator.evaluate(Calibrator.bundled(), new Analyzer.Options(o.lexicon(), t, Feedback.NONE, DeskRules.NONE));
    }

    public Calibrator.VerdictScore verdictScore(Thresholds t) {
        return Calibrator.evaluate(verdictArchive(), options().feedback(), options().with(t));
    }

    /** Finds the thresholds that best fit the labelled sample and your verdicts. Doesn't save them. */
    public Calibrator.Suggestion calibrate(Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        return Calibrator.tune(Calibrator.bundled(), verdictArchive(), options(), progress == null ? x -> { } : progress, cancelled);
    }

    // ------------------------------------------------------------------ Claude (P4)

    private com.linkeddesk.ai.ClaudeService claude;
    private com.linkeddesk.ai.LinkChecks linkChecks;

    /** All Claude calls, within the monthly budget, with the usage log and answer cache. */
    public synchronized com.linkeddesk.ai.ClaudeService claude() {
        if (claude == null) claude = new com.linkeddesk.ai.ClaudeService(settings, AppPaths.home());
        return claude;
    }

    /** Claude's verdicts on links (link-checks.tsv). */
    public synchronized com.linkeddesk.ai.LinkChecks linkChecks() {
        if (linkChecks == null) linkChecks = new com.linkeddesk.ai.LinkChecks(AppPaths.home()).load();
        return linkChecks;
    }

    /** "Ask Claude why" for one link (cached; counts against the budget). */
    public String explainLink(com.linkeddesk.model.StoryLink link) throws Exception {
        return claude().ask("explain", com.linkeddesk.ai.ClaudeClient.explainPrompt(link), 700);
    }

    /** Today's digest if already written. */
    public com.linkeddesk.ai.Digest todaysDigest() {
        try { return com.linkeddesk.ai.Digest.load(AppPaths.home(), java.time.LocalDate.now()); }
        catch (IOException e) { return null; }
    }

    /** Has Claude write today's digest from the briefing (once a day unless {@code again}). */
    public com.linkeddesk.ai.Digest writeDigest(com.linkeddesk.analysis.Briefing b, boolean again) throws Exception {
        com.linkeddesk.ai.Digest have = todaysDigest();
        if (have != null && !again) return have;
        if (b.isEmpty()) throw new IllegalStateException("Nothing downloaded in the last 24 hours to write about.");
        java.util.Map<String, StoryCluster> refs = com.linkeddesk.ai.Digest.stories(b);
        String text = claude().ask("digest", com.linkeddesk.ai.Digest.prompt(b, refs), com.linkeddesk.ai.Digest.MAX_TOKENS);
        com.linkeddesk.ai.Digest d = com.linkeddesk.ai.Digest.parse(text, com.linkeddesk.ai.Cite.ids(refs), System.currentTimeMillis());
        d.save(AppPaths.home());
        Logs.run("digest", "Claude digest written: " + d.reported().size() + " reported, " + d.inference().size() + " inferred points", String.join("\n", d.problems()), 0);
        return d;
    }

    /**
     * Has Claude check up to {@code n} of the day's strongest new links it hasn't checked yet (one request).
     * Returns how many were checked.
     */
    public int checkLinks(com.linkeddesk.analysis.Briefing b, int n) throws Exception {
        if (n <= 0) return 0;
        var checks = linkChecks();
        long since = b.now() - 86_400_000L;
        List<com.linkeddesk.model.StoryLink> cand = new ArrayList<>(b.newLinks());
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<com.linkeddesk.model.StoryLink> all = new ArrayList<>();
        for (StoryCluster c : b.result().stories()) for (var l : c.links) if (c.lastSeen >= since && l.to().lastSeen >= since) all.add(l);
        all.sort(Comparator.comparingDouble(com.linkeddesk.model.StoryLink::score).reversed());
        cand.addAll(all);
        List<com.linkeddesk.model.StoryLink> pick = new ArrayList<>();
        for (var l : cand) {
            String key = l.from().id.compareTo(l.to().id) < 0 ? l.from().id + "|" + l.to().id : l.to().id + "|" + l.from().id;
            if (!seen.add(key) || l.confirmed() || checks.find(l.from(), l.to()) != null) continue;
            pick.add(l);
            if (pick.size() >= n) break;
        }
        if (pick.isEmpty()) return 0;
        String text = claude().ask("check", com.linkeddesk.ai.LinkChecks.prompt(pick), 40 + pick.size() * com.linkeddesk.ai.LinkChecks.MAX_TOKENS_PER_LINK);
        var verdicts = com.linkeddesk.ai.LinkChecks.parse(text, pick, settings.model(), System.currentTimeMillis());
        checks.add(verdicts);
        return verdicts.size();
    }

    /** After the daily download: the digest and link checks, if switched on. Returns what happened, for the log. */
    public String dailyClaude() {
        settings.reload();
        if (!settings.hasApiKey() || settings.claudeBudget() <= 0) return "Claude: off (no API key or budget).";
        StringBuilder sb = new StringBuilder();
        try {
            com.linkeddesk.analysis.Briefing b = briefing();
            if (settings.claudeDigest()) { writeDigest(b, false); sb.append("Digest written. "); }
            int n = checkLinks(b, settings.claudeCheckLinks());
            sb.append(n).append(" links checked. ");
        } catch (com.linkeddesk.ai.ClaudeService.BudgetException e) {
            sb.append(e.getMessage());
        } catch (Exception e) {
            Logs.error("Daily Claude digest / link checks", e);
            sb.append("Claude: ").append(e.getMessage());
        }
        sb.append(' ').append(claude().summary());
        return sb.toString().trim();
    }

    /** Finds the saved stories that best match a question (all history unless the question names a time). */
    public List<StoryCluster> findForQuestion(String question, int[] searched) throws IOException {
        Analyzer.Options opt = options();
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (var t : com.linkeddesk.analysis.Text.tokens(question, opt.lexicon())) terms.add(t.term());
        for (String w : question.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && Character.isUpperCase(w.charAt(0)) && !com.linkeddesk.analysis.Text.STOP.contains(w.toLowerCase(java.util.Locale.ROOT)))
                terms.add(w.toLowerCase(java.util.Locale.ROOT));   // names as written ("Fed" as well as Federal Reserve)
        }
        terms.removeIf(t -> t.length() < 3 || t.matches("(ask|tell|explain|happen|happened|happening|what|news|latest|recent|month|week|today|yesterday)"));
        if (terms.isEmpty()) return List.of();
        long since = timeWords(question.toLowerCase(java.util.Locale.ROOT));
        // Each question word, with its nationality words ("belgium" also finds "Belgian").
        List<String> termList = new ArrayList<>(terms);
        List<SearchQuery> each = new ArrayList<>();
        for (String t : termList) {
            StringBuilder alt = new StringBuilder(t.contains(" ") ? "\"" + t + "\"" : t);
            for (String d : com.linkeddesk.analysis.Text.demonymsOf(t)) alt.append(" OR ").append(d);
            each.add(new SearchQuery(alt.toString()));
        }
        SearchQuery sq = new SearchQuery(String.join(" OR ", each.stream().map(SearchQuery::text).toList()));
        java.util.Map<Article, int[]> hitsOf = new java.util.HashMap<>();   // per article: which words, in headline?
        var src = sources();
        store.find(sq, a -> a.published >= since && !src.muted(a.source) && sq.matches(a), a -> {
            Article headlineOnly = new Article(a.id, a.url, a.title, "", "", a.feedCategory, 0, 0);
            int[] h = new int[each.size()];
            for (int i = 0; i < each.size(); i++) if (each.get(i).matches(a)) h[i] = each.get(i).matches(headlineOnly) ? 2 : 1;
            synchronized (hitsOf) { hitsOf.put(a, h); }
        });
        searched[0] = hitsOf.size();
        // Rare words count more: "Belgium" says more than "strike" about what the question is after.
        double[] weight = new double[each.size()];
        for (int i = 0; i < each.size(); i++) {
            int df = 0;
            for (int[] h : hitsOf.values()) if (h[i] > 0) df++;
            weight[i] = df == 0 ? 0 : Math.log(1.0 + (double) hitsOf.size() / df) + 0.1;
        }
        java.util.Map<Article, Double> score = new java.util.HashMap<>();
        for (var e : hitsOf.entrySet()) {
            double sc = 0;
            for (int i = 0; i < weight.length; i++) if (e.getValue()[i] > 0) sc += weight[i] * (e.getValue()[i] == 2 ? 1.5 : 1.0);
            score.put(e.getKey(), sc);
        }
        List<Article> top = new ArrayList<>(score.keySet());
        top.sort(Comparator.comparingDouble((Article a) -> score.get(a)).reversed().thenComparing(Comparator.comparingLong((Article a) -> a.published).reversed()));
        if (top.size() > 400) top = new ArrayList<>(top.subList(0, 400));
        Analyzer.Result r = Analyzer.analyze(top, opt);
        java.util.Map<StoryCluster, Double> best = new java.util.HashMap<>();
        double bestAll = 0;
        for (StoryCluster c : r.stories()) {
            double b = 0;
            for (Article a : c.articles) b = Math.max(b, score.getOrDefault(a, 0.0));
            best.put(c, b);
            bestAll = Math.max(bestAll, b);
        }
        double cut = bestAll * 0.6;   // weak matches (only the common words) are left out
        List<StoryCluster> stories = new ArrayList<>();
        for (StoryCluster c : r.stories()) if (best.get(c) >= cut) stories.add(c);
        stories.sort(Comparator.comparingDouble((StoryCluster c) -> best.get(c)).reversed()
                .thenComparing(Comparator.comparingInt(StoryCluster::sourceCount).reversed())
                .thenComparing(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed()));
        if (stories.size() > com.linkeddesk.ai.ArchiveQA.MAX_STORIES) stories = new ArrayList<>(stories.subList(0, com.linkeddesk.ai.ArchiveQA.MAX_STORIES));
        stories.sort(Comparator.comparingLong(c -> c.firstSeen));   // oldest first reads like a story
        return stories;
    }

    /** "today", "yesterday", "this week", "last week", "this month", "last month", "this year" -> start time, else 0. */
    static long timeWords(String q) {
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        java.time.LocalDate d = java.time.LocalDate.now(z);
        java.time.LocalDate from = null;
        if (q.contains("today")) from = d;
        else if (q.contains("yesterday")) from = d.minusDays(1);
        else if (q.contains("this week") || q.contains("past week") || q.contains("last 7 days")) from = d.minusDays(7);
        else if (q.contains("last week")) from = d.minusDays(14);
        else if (q.contains("this month") || q.contains("past month") || q.contains("last 30 days")) from = d.minusDays(31);
        else if (q.contains("last month")) from = d.minusDays(62);
        else if (q.contains("this year")) from = d.withDayOfYear(1);
        return from == null ? 0 : from.atStartOfDay(z).toInstant().toEpochMilli();
    }

    /** Answers a question from your archive with Claude, citing the stories. Claude isn't asked if nothing matches. */
    public com.linkeddesk.ai.ArchiveQA.Answer askArchive(String question) throws Exception {
        int[] searched = {0};
        List<StoryCluster> stories = findForQuestion(question, searched);
        var refs = com.linkeddesk.ai.Cite.number(stories);
        if (refs.isEmpty()) return new com.linkeddesk.ai.ArchiveQA.Answer(question, "", refs, List.of(), List.of(), searched[0]);
        String text = claude().ask("ask", com.linkeddesk.ai.ArchiveQA.prompt(question, refs), com.linkeddesk.ai.ArchiveQA.MAX_TOKENS);
        return com.linkeddesk.ai.ArchiveQA.parse(question, text, refs, searched[0]);
    }

    // ------------------------------------------------------------------ after each download (P5)

    private com.linkeddesk.alerts.Mailer mailer = new com.linkeddesk.alerts.Mailer();

    /** For tests: a mailer that trusts a test certificate. */
    public void useMailer(com.linkeddesk.alerts.Mailer m) { this.mailer = m; }

    /**
     * After a download: one grouped notification for new watchlist matches (and new links between desks, if
     * switched on); held during quiet hours. Returns what was shown, or null.
     */
    public com.linkeddesk.alerts.Alerts.Alert notifyAfterDownload(FetchReport r, com.linkeddesk.alerts.Alerts.Display display) throws IOException {
        settings.reload();
        if (!settings.notifications()) return null;
        var hits = watches().newHits(sources().visible(r.addedArticles));
        int links = 0;
        if (settings.notifyLinks() && !r.addedArticles.isEmpty()) {
            java.util.Set<String> added = new java.util.HashSet<>();
            for (Article a : r.addedArticles) added.add(a.id);
            for (var l : briefing().newLinks()) {
                boolean fresh = l.from().articles.stream().anyMatch(a -> added.contains(a.id)) || l.to().articles.stream().anyMatch(a -> added.contains(a.id));
                if (fresh) links++;
            }
        }
        var alert = com.linkeddesk.alerts.Alerts.compose(hits, links);
        var shown = new com.linkeddesk.alerts.Alerts(AppPaths.home(), display)
                .deliver(alert, java.time.LocalTime.now(), settings.quietFrom(), settings.quietTo());
        if (!alert.isEmpty()) Logs.run("notify", (shown == null ? "held for after quiet hours: " : "shown: ") + alert.title(), alert.text(), 0);
        return shown;
    }

    // ------------------------------------------------------------------ export and the weekly report (EXP-F01, EXP-F02)

    /** Stories from articles published in [from, to). */
    public Analyzer.Result analyzeRange(long from, long to) {
        List<Article> in = new ArrayList<>();
        for (Article a : store.since(from)) if (a.published < to) in.add(a);
        return tieTickers(Analyzer.analyze(sources().visible(in), options()));
    }

    /** The report for the week (Monday to Sunday) holding this day. */
    public com.linkeddesk.export.Doc weeklyReport(java.time.LocalDate anyDay) {
        java.time.LocalDate mon = com.linkeddesk.export.WeeklyReport.weekStart(anyDay);
        long from = com.linkeddesk.export.WeeklyReport.startMillis(mon), to = com.linkeddesk.export.WeeklyReport.endMillis(mon);
        Analyzer.Result r = analyzeRange(from, to);
        var ws = watches();
        java.util.Map<String, List<StoryCluster>> watchMap = new java.util.LinkedHashMap<>();
        var hits = ws.stories(r.stories());
        for (var w : ws.all()) {
            List<StoryCluster> l = new ArrayList<>();
            for (var e : hits.entrySet()) if (e.getValue().contains(w)) l.add(e.getKey());
            if (!l.isEmpty()) watchMap.put(w.name(), l);
        }
        java.util.Map<String, List<StoryCluster>> tickerMap = new java.util.LinkedHashMap<>();
        var tk = tickers();
        for (var t : tk.all()) {
            List<StoryCluster> l = new ArrayList<>();
            for (StoryCluster c : r.stories()) if (tk.of(c).contains(t.symbol())) l.add(c);
            if (!l.isEmpty()) tickerMap.put(t.label(), l);
        }
        List<com.linkeddesk.ai.Digest> digests = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            try { var dg = com.linkeddesk.ai.Digest.load(AppPaths.home(), mon.plusDays(i)); if (dg != null) digests.add(dg); } catch (IOException ignored) { }
        }
        var src = sources();
        return com.linkeddesk.export.WeeklyReport.build(mon, r, new com.linkeddesk.export.WeeklyReport.Extras(regions(), src::coverage, watchMap, tickerMap, digests));
    }

    // ------------------------------------------------------------------ archive upkeep (STO-F03, STO-F04)

    /** Brings the search index up to date (the first time: every month). Returns months indexed. */
    public int refreshIndex() throws IOException {
        long t0 = System.nanoTime();
        int n = store.index().refresh();
        if (n > 0) Logs.run("index", "search index: " + n + (n == 1 ? " month" : " months") + " indexed", "", (System.nanoTime() - t0) / 1_000_000);
        return n;
    }

    /** Compresses months older than the setting (6 by default). Returns the months compressed. */
    public List<String> compressOldMonths() throws IOException {
        settings.reload();
        int m = settings.compressAfterMonths();
        if (m <= 0) return List.of();
        List<String> done = store.compressOlderThan(m);
        if (!done.isEmpty()) Logs.run("archive", "compressed " + String.join(", ", done), "", 0);
        return done;
    }

    /** Backs up now to the folder in Settings. */
    public com.linkeddesk.store.Backup.Info backupNow() throws IOException {
        settings.reload();
        if (settings.backupFolder().isBlank()) throw new IOException("Choose a backup folder first (Tools > Archive and backup).");
        var info = com.linkeddesk.store.Backup.create(AppPaths.home(), java.nio.file.Path.of(settings.backupFolder()), AppPaths.VERSION, settings.backupKeep());
        settings.reload();
        settings.setBackupLast(System.currentTimeMillis());
        settings.save();
        Logs.run("backup", "backed up: " + info.describe(), "", 0);
        return info;
    }

    /** After the daily download: compress old months, update the index, and the weekly backup if it's due. */
    public String maintenance() {
        List<String> out = new ArrayList<>();
        try { var c = compressOldMonths(); if (!c.isEmpty()) out.add("Compressed " + String.join(", ", c) + "."); }
        catch (Exception e) { Logs.error("Compressing old months", e); out.add("Compressing old months failed: " + e.getMessage()); }
        try { refreshIndex(); } catch (Exception e) { Logs.error("Updating the search index", e); }
        settings.reload();
        if (settings.backupWeekly() && !settings.backupFolder().isBlank() && System.currentTimeMillis() - settings.backupLast() > 7L * 86_400_000L - 3_600_000L) {
            try { out.add("Backed up to " + backupNow().file() + "."); }
            catch (Exception e) { Logs.error("Weekly backup", e); out.add("The weekly backup failed: " + e.getMessage()); }
        }
        return String.join(" ", out);
    }

    /** Puts a backup back; the app then reads the restored archive and settings. Returns where the old files went. */
    public java.nio.file.Path restore(java.nio.file.Path zip) throws IOException {
        java.nio.file.Path aside = com.linkeddesk.store.Backup.restore(zip, AppPaths.home());
        settings.reload();
        store.load();
        synchronized (this) { options = null; watches = null; tickers = null; sources = null; reads = null; }
        Logs.run("backup", "restored " + zip.getFileName(), aside == null ? "" : "previous files: " + aside, 0);
        return aside;
    }

    /** Shows notifications held during quiet hours, once they're over (the window checks every few minutes). */
    public com.linkeddesk.alerts.Alerts.Alert flushHeldAlerts(com.linkeddesk.alerts.Alerts.Display display) throws IOException {
        settings.reload();
        var al = new com.linkeddesk.alerts.Alerts(AppPaths.home(), display);
        if (al.held().isEmpty()) return null;
        var shown = al.deliver(new com.linkeddesk.alerts.Alerts.Alert("", "", 0), java.time.LocalTime.now(), settings.quietFrom(), settings.quietTo());
        if (shown != null) Logs.run("notify", "shown after quiet hours: " + shown.title(), shown.text(), 0);
        return shown;
    }

    /** Emails today's briefing (once a day, after the daily download; {@code now} = send now anyway, e.g. a test). */
    public String emailBriefing(boolean now) throws Exception {
        settings.reload();
        if (!now && !settings.emailEnabled()) return "Email: off.";
        String today = java.time.LocalDate.now().toString();
        if (!now && today.equals(settings.emailLastSent())) return "Email: already sent today.";
        com.linkeddesk.analysis.Briefing b = briefing();
        if (b.isEmpty() && !now) return "Email: nothing new in the last 24 hours, not sent.";
        var mail = com.linkeddesk.alerts.EmailDigest.build(b, todaysDigest(), regions());
        var to = com.linkeddesk.alerts.Mailer.Config.list(settings.emailTo());
        var cfg = new com.linkeddesk.alerts.Mailer.Config(settings.smtpHost(), settings.smtpPort(), settings.smtpSecurity(), settings.smtpUser(),
                settings.smtpPassword(), settings.emailFrom(), to);
        try {
            mailer.send(cfg, mail.subject(), mail.text(), mail.html());
        } catch (IOException e) {
            Logs.run("email", "failed: " + e.getMessage(), String.join("\n", mailer.transcript()), 0);
            throw e;
        }
        settings.reload();
        settings.setEmailLastSent(today);
        settings.save();
        String done = "Briefing emailed to " + String.join(", ", to) + ".";
        Logs.run("email", done, mail.subject(), 0);
        return done;
    }
}
