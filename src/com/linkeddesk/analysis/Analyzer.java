package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.StoryThread;
import com.linkeddesk.model.Storyline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a pile of articles into stories, links, storylines and threads.
 *
 * 1. Categorize each article into a desk (your desk rules first).
 * 2. Build a TF-IDF vector per article: headline words count double, names and acronyms extra, multi-word
 *    names ("Strait of Hormuz") count as one term besides their words, and aliases ("Fed") become one name.
 * 3. Group articles about the same event: an article joins the most similar recent story (cosine >= the
 *    desk's same-event threshold, within {@link #EVENT_WINDOW_MS}), otherwise starts a new one.
 * 4. Storylines: a story that continues an earlier one (the same event days later) is connected to it.
 * 5. Link different stories that share distinctive names or topics (cosine of key terms >= the desk's link
 *    threshold, at least one shared name, within {@link #LINK_WINDOW_MS}). Your verdicts are applied.
 * 6. Threads are topics that recur across several linked stories.
 */
public final class Analyzer {
    static final long EVENT_WINDOW_MS = 48L * 3600_000;
    static final long MAX_EVENT_SPAN_MS = 5L * 24 * 3600_000;
    static final long LINK_WINDOW_MS = 10L * 24 * 3600_000;
    static final long LINEAGE_GAP_MS = 7L * 24 * 3600_000;
    static final long LINEAGE_MIN_LATER_MS = 18L * 3600_000;
    static final int MAX_LINEAGE_CANDIDATES = 60;
    static final int MAX_LINKS_PER_STORY = 10;
    /** Extra weight for multi-word names, and more for a name plus an event word ("Iran war"). */
    static final double PHRASE_BONUS = 1.0, EVENT_BONUS = 3.0;

    private static double bonus(Text.Token t) { return t.event() ? EVENT_BONUS : t.phrase() ? PHRASE_BONUS : 0; }
    static final int MAX_THREAD_CANDIDATES = 400;

    /** What the analysis uses besides the articles: word lists, thresholds and your corrections. */
    public record Options(Lexicon lexicon, Thresholds thresholds, Feedback feedback, DeskRules desks) {
        public static final Options DEFAULT = new Options(Lexicon.defaults(), Thresholds.defaults(), Feedback.NONE, DeskRules.NONE);
        public Options with(Thresholds t) { return new Options(lexicon, t, feedback, desks); }
        public Options with(Lexicon l) { return new Options(l, thresholds, feedback, desks); }
    }

    /** A word and how many links it helps create (for spotting words that are too generic). */
    public record TermUse(String term, int links, int stories) {}

    public record Result(List<StoryCluster> stories, List<StoryThread> threads, int articleCount,
                         List<Storyline> storylines, List<TermUse> linkTerms) {
        public Result(List<StoryCluster> stories, List<StoryThread> threads, int articleCount) {
            this(stories, threads, articleCount, List.of(), List.of());
        }
    }

    private record Doc(Article a, Map<String, Double> vec, List<String> top) {}

    private final Options opt;
    private final Map<String, Map<String, Integer>> surfaces = new HashMap<>();
    private final Map<String, int[]> entityVotes = new HashMap<>(); // [entity, notEntity]
    private final Map<Article, DeskRules.Rule> articleRules = new IdentityHashMap<>();
    private final Map<StoryCluster, Map<String, Double>> centroids = new HashMap<>();
    private final List<TermUse> termUse = new ArrayList<>();

    private Analyzer(Options opt) { this.opt = opt; }

    public static Result analyze(List<Article> articles) { return analyze(articles, Options.DEFAULT); }

    public static Result analyze(List<Article> articles, Options options) { return new Analyzer(options).run(articles); }

    private Result run(List<Article> input) {
        long p0 = System.nanoTime();
        List<Article> articles = new ArrayList<>(input);
        articles.sort(Comparator.comparingLong(a -> a.published));
        for (Article a : articles) {
            a.setCategory(Categorizer.categorize(a));
            DeskRules.Rule r = opt.desks().isEmpty() ? null : opt.desks().forArticle(a);
            if (r != null) { a.setCategory(r.desk()); articleRules.put(a, r); }
        }

        // ---- term frequencies (in parallel, one article per task) and document frequencies
        Lexicon lex = opt.lexicon();
        record Tok(Map<String, Double> tf, Map<String, Double> tentative, List<Text.Token> tokens) {}
        List<Tok> toks = articles.parallelStream().map(a -> {
            List<Text.Token> title = Text.tokens(a.title, lex), body = Text.tokens(a.summary, lex);
            Map<String, Double> tf = new HashMap<>(), tentative = new HashMap<>();
            // Multi-word names count extra: "Strait of Hormuz" or "Iran war" says more than any one word.
            for (Text.Token t : title) (t.phrase() && t.titleCase() ? tentative : tf).merge(t.term(), 2.0 + (t.entity() ? 1.0 : 0) + bonus(t), Double::sum);
            for (Text.Token t : body) (t.phrase() && t.titleCase() ? tentative : tf).merge(t.term(), 1.0 + (t.entity() ? 0.5 : 0) + bonus(t), Double::sum);
            List<Text.Token> all = new ArrayList<>(title.size() + body.size());
            all.addAll(title);
            all.addAll(body);
            return new Tok(tf, tentative, all);
        }).toList();
        // A capitalized run from a Title Case Headline is only a name if it also appears in ordinary text
        // (or is a known name); otherwise "Paralyzes Air Travel" would become a "name".
        Set<String> phraseOk = new HashSet<>();
        for (Tok tk : toks) for (Text.Token t : tk.tokens()) if (t.phrase() && (!t.titleCase() || lex.display(t.term()) != null)) phraseOk.add(t.term());
        List<Map<String, Double>> tfs = new ArrayList<>(articles.size());
        Map<String, Integer> df = new HashMap<>();
        for (Tok tk : toks) {
            for (var e : tk.tentative().entrySet()) if (phraseOk.contains(e.getKey())) tk.tf().merge(e.getKey(), e.getValue(), Double::sum);
            tfs.add(tk.tf());
            for (String t : tk.tf().keySet()) df.merge(t, 1, Integer::sum);
            for (Text.Token t : tk.tokens()) {
                if (t.phrase() && !phraseOk.contains(t.term())) continue;
                surfaces.computeIfAbsent(t.term(), k -> new HashMap<>()).merge(t.surface(), 1, Integer::sum);
                int[] v = entityVotes.computeIfAbsent(t.term(), k -> new int[2]);
                v[t.entity() ? 0 : 1]++;
            }
        }
        int n = Math.max(1, articles.size());

        Doc[] docArr = new Doc[articles.size()];
        java.util.stream.IntStream.range(0, articles.size()).parallel().forEach(i -> {
            Map<String, Double> vec = new HashMap<>();
            double sq = 0;
            for (var e : tfs.get(i).entrySet()) {
                double idf = Math.log((n + 1.0) / (df.get(e.getKey()) + 1.0)) + 1.0;
                double w = (1 + Math.log(e.getValue())) * idf;
                vec.put(e.getKey(), w);
                sq += w * w;
            }
            double norm = Math.sqrt(sq);
            if (norm > 0) vec.replaceAll((k, v) -> v / norm);
            List<String> top = vec.entrySet().stream()
                    .filter(e -> df.get(e.getKey()) >= 2)
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(10).map(Map.Entry::getKey).toList();
            docArr[i] = new Doc(articles.get(i), vec, top);
        });
        List<Doc> docs = java.util.Arrays.asList(docArr);

        long p1 = System.nanoTime();
        List<StoryCluster> stories = cluster(docs);
        long p2 = System.nanoTime();
        List<Storyline> storylines = lineage(stories);
        long p3 = System.nanoTime();
        link(stories);
        long p4 = System.nanoTime();
        List<StoryThread> threads = threads(stories);
        long p5 = System.nanoTime();
        if (Boolean.getBoolean("linkeddesk.profile"))
            System.out.printf("  [profile] vectors %.2fs, grouping %.2fs, storylines %.2fs, linking %.2fs, threads %.2fs%n",
                    (p1 - p0) / 1e9, (p2 - p1) / 1e9, (p3 - p2) / 1e9, (p4 - p3) / 1e9, (p5 - p4) / 1e9);
        stories.sort(Comparator.comparingInt(StoryCluster::sourceCount).reversed()
                .thenComparing(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed()));
        return new Result(stories, threads, articles.size(), storylines, List.copyOf(termUse));
    }

    // ------------------------------------------------------------------ same-event clustering

    private static final class Building {
        final StoryCluster story;
        final Map<String, Double> sum = new HashMap<>();
        final List<Doc> docs = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final int idx;
        double sumSq;
        Building(StoryCluster s, int idx) { story = s; this.idx = idx; }

        double similarity(Map<String, Double> vec) {
            if (sumSq <= 0) return 0;
            double dot = 0;
            for (var e : vec.entrySet()) {
                Double s = sum.get(e.getKey());
                if (s != null) dot += s * e.getValue();
            }
            return dot / Math.sqrt(sumSq);
        }

        void add(Doc d) {
            docs.add(d);
            ids.add(d.a().id);
            story.add(d.a());
            for (var e : d.vec().entrySet()) {
                double old = sum.getOrDefault(e.getKey(), 0.0);
                double nw = old + e.getValue();
                sum.put(e.getKey(), nw);
                sumSq += nw * nw - old * old;
            }
        }
    }

    private List<StoryCluster> cluster(List<Doc> docs) {
        // Articles you took out of a story stay apart from the articles of that story.
        Map<String, Set<String>> apart = new HashMap<>();
        for (Feedback.Entry e : opt.feedback().entries()) {
            if (e.kind() != Feedback.Kind.SPLIT) continue;
            for (String x : e.a()) for (String o : e.b()) {
                apart.computeIfAbsent(x, k -> new HashSet<>()).add(o);
                apart.computeIfAbsent(o, k -> new HashSet<>()).add(x);
            }
        }
        List<Building> clusters = new ArrayList<>();
        // term -> stories that used it, oldest first. Stories that can no longer take new articles
        // (quiet for more than 48 h) are dropped from the front, so the work stays proportional to
        // the news of the last two days, however long the period being analysed.
        Map<String, ArrayDeque<Integer>> index = new HashMap<>();
        for (Doc d : docs) {
            Set<Integer> cand = new HashSet<>();
            long cutoff = d.a().published - EVENT_WINDOW_MS;
            for (String t : d.top()) {
                ArrayDeque<Integer> l = index.get(t);
                if (l == null) continue;
                while (!l.isEmpty() && clusters.get(l.peekFirst()).story.lastSeen < cutoff) l.pollFirst();
                cand.addAll(l);
            }
            Set<String> keepApart = apart.get(d.a().id);
            Building best = null;
            double bestSim = opt.thresholds().sameEvent(d.a().category());
            for (int ci : cand) {
                Building b = clusters.get(ci);
                if (d.a().published - b.story.lastSeen > EVENT_WINDOW_MS) continue;
                if (d.a().published - b.story.firstSeen > MAX_EVENT_SPAN_MS) continue;
                if (keepApart != null && !java.util.Collections.disjoint(keepApart, b.ids)) continue;
                double s = b.similarity(d.vec());
                if (s >= bestSim) { bestSim = s; best = b; }
            }
            if (best == null) {
                best = new Building(new StoryCluster(d.a().id), clusters.size());
                clusters.add(best);
            }
            int idx = best.idx;
            best.add(d);
            for (String t : d.top().subList(0, Math.min(6, d.top().size()))) {
                ArrayDeque<Integer> l = index.computeIfAbsent(t, k -> new ArrayDeque<>());
                if (l.isEmpty() || l.peekLast() != idx) l.addLast(idx);
            }
        }

        List<StoryCluster> out = new ArrayList<>(clusters.size());
        for (Building b : clusters) {
            finish(b);
            out.add(b.story);
        }
        return out;
    }

    private void finish(Building b) {
        StoryCluster c = b.story;
        double norm = Math.sqrt(b.sumSq);
        Map<String, Double> centroid = new HashMap<>();
        for (var e : b.sum.entrySet()) centroid.put(e.getKey(), e.getValue() / norm);
        centroids.put(c, centroid);

        c.keyTerms = centroid.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(12).map(e -> display(e.getKey())).toList();

        Doc rep = b.docs.get(0);
        double best = -1;
        for (Doc d : b.docs) {
            double dot = 0;
            for (var e : d.vec().entrySet()) dot += e.getValue() * centroid.getOrDefault(e.getKey(), 0.0);
            if (dot > best + 1e-9) { best = dot; rep = d; }
        }
        c.headline = rep.a().title;
        c.summary = rep.a().summary;
        if (c.summary.isEmpty()) {
            for (Doc d : b.docs) if (!d.a().summary.isEmpty()) { c.summary = d.a().summary; break; }
        }

        Map<Category, Integer> votes = new EnumMap<>(Category.class);
        for (Doc d : b.docs) votes.merge(d.a().category(), 1, Integer::sum);
        votes.merge(rep.a().category(), 1, Integer::sum); // the representative breaks ties
        List<Map.Entry<Category, Integer>> ranked = new ArrayList<>(votes.entrySet());
        ranked.sort(Map.Entry.<Category, Integer>comparingByValue().reversed());
        c.category = ranked.get(0).getKey();
        // A story with plenty of coverage from a second desk shows there too.
        if (ranked.size() > 1 && ranked.get(1).getValue() >= 2 && ranked.get(1).getValue() * 3 >= b.docs.size())
            c.also = ranked.get(1).getKey();

        // Your rules: a story rule beats rules on its articles' sources or keywords.
        DeskRules.Rule storyRule = opt.desks().isEmpty() ? null : opt.desks().forStory(c);
        if (storyRule != null) {
            c.category = storyRule.desk();
            c.also = storyRule.also();
            c.deskSetByYou = true;
        } else if (!articleRules.isEmpty()) {
            for (Doc d : b.docs) {
                DeskRules.Rule r = articleRules.get(d.a());
                if (r == null) continue;
                if (r.desk() == c.category) c.deskSetByYou = true;
                if (r.also() != null && r.desk() == c.category) c.also = r.also();
            }
        }
        if (c.also == c.category) c.also = null;
    }

    // ------------------------------------------------------------------ storylines

    /**
     * Connects each story to the earlier story it continues: the same event reported again a day or more later
     * (after the 48-hour grouping window), such as "3 dead in factory fire" and, two days on, "24 dead".
     */
    private List<Storyline> lineage(List<StoryCluster> stories) {
        List<StoryCluster> byStart = new ArrayList<>(stories);
        byStart.sort(Comparator.comparingLong(c -> c.firstSeen));
        Map<StoryCluster, List<String>> top = new HashMap<>();
        Map<StoryCluster, Map<String, Double>> small = new HashMap<>();   // the 25 strongest terms are enough to compare
        for (StoryCluster c : byStart) {
            List<Map.Entry<String, Double>> sorted = centroids.get(c).entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(25).toList();
            Map<String, Double> m = new HashMap<>();
            for (var e : sorted) m.put(e.getKey(), e.getValue());
            small.put(c, m);
            top.put(c, sorted.stream().limit(8).map(Map.Entry::getKey).toList());
        }
        Map<String, ArrayDeque<StoryCluster>> index = new HashMap<>();
        // Stories enter the index only once they are old enough to be "earlier" for the story being looked at.
        int next = 0;
        for (StoryCluster b : byStart) {
            while (next < byStart.size() && byStart.get(next).firstSeen <= b.firstSeen - LINEAGE_MIN_LATER_MS) {
                StoryCluster a = byStart.get(next++);
                for (String t : top.get(a)) index.computeIfAbsent(t, k -> new ArrayDeque<>()).addLast(a);
            }
            Map<StoryCluster, Integer> cand = new IdentityHashMap<>();
            long oldest = b.firstSeen - LINEAGE_GAP_MS - MAX_EVENT_SPAN_MS;
            for (String t : top.get(b)) {
                ArrayDeque<StoryCluster> l = index.get(t);
                if (l == null) continue;
                while (!l.isEmpty() && l.peekFirst().firstSeen < oldest) l.pollFirst();   // too old to be continued
                if (l.size() > MAX_LINEAGE_CANDIDATES) continue;                          // too common a word to tell
                for (StoryCluster a : l) cand.merge(a, 1, Integer::sum);
            }
            StoryCluster best = null;
            double bestSim = opt.thresholds().lineage();
            Map<String, Double> cb = small.get(b);
            for (var ce : cand.entrySet()) {
                if (ce.getValue() < 2) continue;                                          // must share two strong terms
                StoryCluster a = ce.getKey();
                if (b.firstSeen - a.lastSeen > LINEAGE_GAP_MS) continue;
                if (a.lastSeen > b.lastSeen) continue;
                Map<String, Double> ca = small.get(a);
                double dot = 0;
                boolean name = false;
                for (var e : cb.entrySet()) {
                    Double w = ca.get(e.getKey());
                    if (w == null) continue;
                    dot += w * e.getValue();
                    if (isEntity(e.getKey())) name = true;
                }
                if (name && dot >= bestSim) { bestSim = dot; best = a; }
            }
            if (best != null) {
                b.continues = best;
                best.continuedBy.add(b);
            }
        }
        List<Storyline> out = new ArrayList<>();
        for (StoryCluster c : byStart) {
            if (c.continues == null && !c.continuedBy.isEmpty()) out.add(new Storyline(c.storyline()));
        }
        out.sort(Comparator.comparingLong((Storyline s) -> s.latest().lastSeen).reversed());
        return out;
    }

    // ------------------------------------------------------------------ linking

    private void link(List<StoryCluster> stories) {
        int n = stories.size();
        List<Map<String, Double>> keys = new ArrayList<>(n);
        List<Map<String, Double>> fullKeys = new ArrayList<>(n);
        Map<String, Integer> clusterFreq = new HashMap<>();
        for (StoryCluster c : stories) {
            Map<String, Double> k = new LinkedHashMap<>();
            centroids.get(c).entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(15).forEach(e -> k.put(e.getKey(), e.getValue()));
            fullKeys.add(new LinkedHashMap<>(k));
            keys.add(k);
            for (String t : k.keySet()) clusterFreq.merge(t, 1, Integer::sum);
        }
        // Terms that describe a large share of all stories are too generic to link on.
        int generic = Math.max(6, (int) (n * 0.05));
        for (int i = 0; i < n; i++) {
            Map<String, Double> k = keys.get(i);
            k.keySet().removeIf(t -> clusterFreq.get(t) > generic);
            double sq = 0;
            for (double v : k.values()) sq += v * v;
            double norm = Math.sqrt(sq);
            if (norm > 0) k.replaceAll((t, v) -> v / norm);
        }

        // ---- your verdicts
        Map<String, Integer> storyOf = new HashMap<>();
        Map<StoryCluster, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            indexOf.put(stories.get(i), i);
            for (Article a : stories.get(i).articles) storyOf.put(a.id, i);
        }
        Set<Long> wrong = new HashSet<>(), right = new HashSet<>();
        Set<String> weakPairs = new HashSet<>();
        for (Feedback.Entry e : opt.feedback().entries()) {
            if (e.kind() == Feedback.Kind.SPLIT) continue;
            Set<Integer> sa = new HashSet<>(), sb = new HashSet<>();
            for (String id : e.a()) { Integer s = storyOf.get(id); if (s != null) sa.add(s); }
            for (String id : e.b()) { Integer s = storyOf.get(id); if (s != null) sb.add(s); }
            for (int x : sa) for (int y : sb) if (x != y) (e.kind() == Feedback.Kind.WRONG ? wrong : right).add(pair(x, y));
            if (e.kind() == Feedback.Kind.WRONG && e.terms().size() >= 2) weakPairs.add(termPair(e.terms().get(0), e.terms().get(1)));
        }
        right.removeAll(wrong);

        // Visit stories in time order and compare each only with earlier stories within the link window,
        // so the work grows with the news per 10 days, not with the square of the whole period.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingLong(i -> stories.get(i).lastSeen));
        Map<String, ArrayDeque<Integer>> index = new HashMap<>();
        Map<String, Integer> linksPerTerm = new HashMap<>();
        Set<Long> made = new HashSet<>();
        for (int i : order) {
            StoryCluster a = stories.get(i);
            long cutoff = a.lastSeen - LINK_WINDOW_MS;
            Map<Integer, Integer> shared = new HashMap<>();
            for (String t : keys.get(i).keySet()) {
                ArrayDeque<Integer> l = index.computeIfAbsent(t, x -> new ArrayDeque<>());
                while (!l.isEmpty() && stories.get(l.peekFirst()).lastSeen < cutoff) l.pollFirst();
                for (int j : l) shared.merge(j, 1, Integer::sum);
                l.addLast(i);
            }
            for (var e : shared.entrySet()) {
                if (e.getValue() < 2) continue;
                int j = e.getKey();
                StoryCluster b = stories.get(j);
                Map<String, Double> ka = keys.get(i), kb = keys.get(j);
                double dot = 0;
                List<Map.Entry<String, Double>> common = new ArrayList<>();
                boolean nameShared = false;
                for (var t : ka.entrySet()) {
                    Double w = kb.get(t.getKey());
                    if (w == null) continue;
                    double c = t.getValue() * w;
                    dot += c;
                    common.add(Map.entry(t.getKey(), c));
                    if (isEntity(t.getKey())) nameShared = true;
                }
                common.sort(Map.Entry.<String, Double>comparingByValue().reversed());
                List<String> terms = common.stream().limit(5).map(x -> display(x.getKey())).toList();
                if (terms.size() >= 2 && weakPairs.contains(termPair(terms.get(0), terms.get(1)))) dot *= 0.5;
                double min = Math.max(opt.thresholds().link(a.category), opt.thresholds().link(b.category));
                if (dot < min || dot >= 0.95 || !nameShared) continue;
                long key = pair(i, j);
                if (wrong.contains(key)) { a.hiddenLinks++; b.hiddenLinks++; continue; }
                boolean ok = right.contains(key);
                a.links.add(new StoryLink(a, b, dot, terms, ok));
                b.links.add(new StoryLink(b, a, dot, terms, ok));
                made.add(key);
                for (String t : terms) linksPerTerm.merge(t, 1, Integer::sum);
            }
        }
        // Links you confirmed are shown even when the words alone wouldn't link the stories.
        for (long key : right) {
            if (made.contains(key)) continue;
            int i = (int) (key >>> 32), j = (int) key;
            StoryCluster a = stories.get(i), b = stories.get(j);
            Map<String, Double> ka = fullKeys.get(i), kb = fullKeys.get(j);
            double dot = 0;
            List<Map.Entry<String, Double>> common = new ArrayList<>();
            for (var t : ka.entrySet()) {
                Double w = kb.get(t.getKey());
                if (w != null) { dot += t.getValue() * w; common.add(Map.entry(t.getKey(), t.getValue() * w)); }
            }
            common.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<String> terms = common.stream().limit(5).map(x -> display(x.getKey())).toList();
            a.links.add(new StoryLink(a, b, dot, terms, true));
            b.links.add(new StoryLink(b, a, dot, terms, true));
        }
        for (StoryCluster c : stories) {
            c.links.sort(Comparator.comparing((StoryLink l) -> !l.confirmed()).thenComparing(Comparator.comparingDouble(StoryLink::score).reversed()));
            if (c.links.size() > MAX_LINKS_PER_STORY) c.links.subList(MAX_LINKS_PER_STORY, c.links.size()).clear();
        }

        // Words behind the links, for the "too generic" report.
        Map<String, Integer> storiesPerTerm = new HashMap<>();
        for (var e : clusterFreq.entrySet()) storiesPerTerm.merge(display(e.getKey()), e.getValue(), Math::max);
        linksPerTerm.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(300)
                .forEach(e -> termUse.add(new TermUse(e.getKey(), e.getValue(), storiesPerTerm.getOrDefault(e.getKey(), 0))));
    }

    private static long pair(int x, int y) {
        int lo = Math.min(x, y), hi = Math.max(x, y);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static String termPair(String x, String y) {
        String a = x.toLowerCase(Locale.ROOT), b = y.toLowerCase(Locale.ROOT);
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    // ------------------------------------------------------------------ threads

    private static List<StoryThread> threads(List<StoryCluster> stories) {
        Map<String, Set<StoryCluster>> members = new HashMap<>();
        Map<String, Integer> linkCount = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (StoryCluster c : stories) {
            for (StoryLink l : c.links) {
                String key = c.id.compareTo(l.to().id) < 0 ? c.id + l.to().id : l.to().id + c.id;
                if (!seen.add(key)) continue;
                for (String t : l.sharedTerms().subList(0, Math.min(3, l.sharedTerms().size()))) {
                    Set<StoryCluster> m = members.computeIfAbsent(t, k -> new LinkedHashSet<>());
                    m.add(c);
                    m.add(l.to());
                    linkCount.merge(t, 1, Integer::sum);
                }
            }
        }
        List<StoryThread> out = new ArrayList<>();
        for (var e : members.entrySet()) {
            if (e.getValue().size() < 3 || linkCount.get(e.getKey()) < 2) continue;
            List<StoryCluster> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingLong(c -> c.lastSeen));
            out.add(new StoryThread(e.getKey(), list, linkCount.get(e.getKey())));
        }
        // Topics that cover the same stories (e.g. "Jensen", "Huang", "Nvidia") become one thread.
        // Only the largest candidates are considered, each compared with the threads kept so far.
        out.sort(Comparator.comparingInt((StoryThread t) -> t.stories().size()).reversed()
                .thenComparing(Comparator.comparingInt(StoryThread::linkCount).reversed()));
        if (out.size() > MAX_THREAD_CANDIDATES) out = new ArrayList<>(out.subList(0, MAX_THREAD_CANDIDATES));
        List<StoryThread> merged = new ArrayList<>();
        List<Set<StoryCluster>> mergedSets = new ArrayList<>();
        List<List<String>> names = new ArrayList<>();
        outer:
        for (StoryThread t : out) {
            for (int i = 0; i < merged.size(); i++) {
                Set<StoryCluster> ms = mergedSets.get(i);
                int common = 0;
                for (StoryCluster c : t.stories()) if (ms.contains(c)) common++;
                if ((double) common / Math.min(ms.size(), t.stories().size()) >= 0.7) {
                    if (names.get(i).size() < 3) names.get(i).add(t.topic());
                    continue outer;
                }
            }
            merged.add(t);
            mergedSets.add(new HashSet<>(t.stories()));
            names.add(new ArrayList<>(List.of(t.topic())));
        }
        List<StoryThread> result = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            StoryThread t = merged.get(i);
            result.add(new StoryThread(String.join(" · ", names.get(i)), t.stories(), t.linkCount()));
        }
        result.sort(Comparator.comparingInt((StoryThread t) -> t.desks().size()).reversed()
                .thenComparing(Comparator.comparingInt((StoryThread t) -> t.stories().size()).reversed()));
        return result.size() > 80 ? new ArrayList<>(result.subList(0, 80)) : result;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isEntity(String term) {
        int[] v = entityVotes.get(term);
        return v != null && v[0] > v[1];
    }

    private String display(String term) {
        String canonical = opt.lexicon().display(term);
        if (canonical != null) return canonical;
        Map<String, Integer> s = surfaces.get(term);
        if (s == null || s.isEmpty()) return term;
        return s.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }
}
