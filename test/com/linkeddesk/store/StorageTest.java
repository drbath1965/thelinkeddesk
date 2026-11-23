package com.linkeddesk.store;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * P6 storage: the full-text index gives exactly the results of a full scan (STO-F03), old months compress and read
 * back transparently, and a backup restores the same archive on another PC (STO-F04).
 */
public final class StorageTest {
    private static final long DAY = 86_400_000L;

    public static List<Article> real(long shiftTo) throws java.io.IOException {
        List<Article> out = new ArrayList<>();
        long newest = Calibrator.bundled().items().stream().mapToLong(l -> l.article().published).max().getAsLong();
        long shift = shiftTo - newest;
        for (Calibrator.Labelled l : Calibrator.bundled().items()) {
            Article x = l.article();
            out.add(new Article(FeedParser.idFor(x.url), x.url, x.title, x.summary, x.source, x.feedCategory, x.published + shift, x.fetched + shift));
        }
        return out;
    }

    static Article art(String id, String title, String summary, String source, long t) {
        return new Article(id, "https://example.org/" + id, title, summary, source, Category.WORLD, t, t);
    }

    static Set<String> scan(ArticleStore s, SearchQuery q) throws Exception {
        Set<String> ids = Collections.synchronizedSet(new TreeSet<>());
        s.scan(l -> !q.cannotMatch(l), a -> q.hasWords() ? q.matches(a) : q.matchesAll(a), a -> ids.add(a.id));
        return ids;
    }

    static Set<String> find(ArticleStore s, SearchQuery q) throws Exception {
        Set<String> ids = Collections.synchronizedSet(new TreeSet<>());
        s.find(q, a -> q.hasWords() ? q.matches(a) : q.matchesAll(a), a -> ids.add(a.id));
        return ids;
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        c.suite("Full-text search index (STO-F03)");
        Path dir = Files.createTempDirectory("ld-idx").resolve("data");
        ArticleStore store = new ArticleStore(dir);
        store.load();
        List<Article> arts = new ArrayList<>(real(now - 3_600_000L));
        // Older months too, and words that are easy to get wrong.
        for (Article a : real(now - 70 * DAY)) arts.add(new Article("old-" + a.id, a.url + "?old", a.title, a.summary, a.source, a.feedCategory, a.published, a.published));
        arts.add(art("t1", "Zürich bank chief resigns", "The Zürich lender's CEO quit.", "Swissinfo", now - 5 * DAY));
        arts.add(art("t2", "O'Brien says COVID-19 rules return", "Dr. O'Brien on covid-19.", "RTÉ", now - 5 * DAY));
        arts.add(art("t3", "Iranian navy drill", "Iran's navy exercises near Tirana? No: in the Gulf.", "Tasnim", now - 4 * DAY));
        arts.add(art("t4", "Tirana mayor re-elected", "Albania's capital Tirana votes.", "Balkan Insight", now - 4 * DAY));
        arts.add(art("t5", "Chip stocks: $NVDA leads", "Nvidia (NVDA) rose on Nasdaq.", "MarketWatch", now - 3 * DAY));
        arts.add(art("t6", "中国北京市 news", "中国 economy slows", "Xinhua", now - 3 * DAY));
        arts.add(art("t7", "Αθήνα strike", "Greek unions strike in Αθήνα", "Kathimerini", now - 3 * DAY));
        arts.add(art("t8", "U.S. tariffs bite", "The U.S. raised tariffs.", "Reuters", now - 2 * DAY));
        store.addAll(arts);
        String[] queries = {"ukraine", "Ukrain", "iran", "tirana", "zurich", "zürich", "o'brien", "brien", "covid-19", "covid", "$nvda", "nvda",
                "\"strait of hormuz\"", "drone OR drones", "russia -ukraine", "trump putin", "中国", "北京", "αθήνα", "u.s.", "u.s. tariffs",
                "source:bbc ukraine", "storm", "storm OR flood -lisbon", "anthropic nvidia", "the", "a", "x"};
        int months = store.index().refresh();
        c.that(months >= 2, "the index is built per month (" + months + " months)");
        boolean allSame = true;
        StringBuilder diff = new StringBuilder();
        for (String qs : queries) {
            SearchQuery q = new SearchQuery(qs);
            Set<String> a = scan(store, q), b = find(store, q);
            if (!a.equals(b)) { allSame = false; diff.append(qs).append(": scan ").append(a.size()).append(" index ").append(b.size()).append("; "); }
        }
        c.that(allSame, "the index finds exactly what a full scan finds, for " + queries.length + " queries (accents, apostrophes, COVID-19, $NVDA, "
                + "phrases, OR, -word, source:, Chinese, Greek, U.S.)" + (allSame ? "" : " — " + diff));
        c.that(find(store, new SearchQuery("iran")).contains("t3") && !find(store, new SearchQuery("iran")).contains("t4"),
                "words match at word starts: iran finds Iranian, not Tirana");
        c.that(find(store, new SearchQuery("zurich")).contains("t1"), "accents don't matter: zurich finds Zürich");

        // A growing month: what's added after the index was built is found too.
        store.addAll(List.of(art("new1", "Volcano erupts near Reykjavik", "Lava reached the road.", "RÚV", now - 60_000L)));
        c.that(find(store, new SearchQuery("reykjavik")).contains("new1"), "articles added after the index was built are found (the new part is scanned)");
        c.that(store.index().stale().isEmpty(), "(a small addition doesn't make the month's index stale)");

        // Newest-first: count from the index, read only the newest.
        SearchQuery common = new SearchQuery("ukraine OR russia");
        SearchIndex.Newest nw = store.index().newest(common, 5, 0, 0);
        Set<String> all = scan(store, common);
        List<Article> byTime = new ArrayList<>();
        store.scan(common::matches, byTime::add);
        byTime.sort(Comparator.comparingLong((Article a) -> a.published).reversed());
        c.that(nw != null && nw.total() == all.size() && nw.newest().size() == 5, "plain words: the index counts every match (" + all.size() + ") and reads the newest 5");
        c.that(nw != null && nw.newest().get(0).published == byTime.get(0).published && nw.newest().get(4).published == byTime.get(4).published, "… and they are the newest");
        c.that(store.index().newest(new SearchQuery("\"strait of hormuz\""), 5, 0, 0) == null && store.index().newest(new SearchQuery("russia -ukraine"), 5, 0, 0) == null,
                "phrases and -words are checked article by article instead");

        // A damaged or missing index never changes results.
        Path idxFile = Files.list(store.index().dir()).filter(p -> p.toString().endsWith(".idx")).findFirst().get();
        Files.write(idxFile, "garbage".getBytes(StandardCharsets.UTF_8));
        c.eq(scan(store, new SearchQuery("ukraine")), find(store, new SearchQuery("ukraine")), "a damaged index file is ignored (that month is scanned)");
        c.that(store.index().stale().contains(dir.resolve(idxFile.getFileName().toString().replace(".idx", ".tsv"))), "… and rebuilt next time");
        store.index().refresh();
        c.that(store.index().stale().isEmpty(), "refresh rebuilds it");

        // ------------------------------------------------------------------ STO-F04 compression
        c.suite("Compressing old months (STO-F04)");
        int before = store.size();
        Set<String> beforeIds = scan(store, new SearchQuery("the OR a OR in OR of"));
        long oldTime = now - 300 * DAY;
        List<Article> old = new ArrayList<>();
        for (int i = 0; i < 3000; i++) old.add(art("o" + i, "Archive story " + i + " about harbour cranes " + (i % 7 == 0 ? "Hamburg" : "Rotterdam"),
                "Port workers " + i + " in the old days. " + "x".repeat(i % 50), "Old Gazette", oldTime + i * 1000L));
        store.addAll(old);
        Path oldFile = dir.resolve("articles-" + YearMonth.from(java.time.Instant.ofEpochMilli(oldTime).atZone(ZoneId.systemDefault())) + ".tsv");
        long rawSize = Files.size(oldFile);
        List<String> done = store.compressOlderThan(6);
        Path gz = dir.resolve(oldFile.getFileName() + ".gz");
        c.that(done.size() == 1 && Files.exists(gz) && !Files.exists(oldFile), "months older than 6 months are compressed: " + done);
        c.that(Files.size(gz) < rawSize / 3, String.format("to a fraction of the size (%,d -> %,d bytes)", rawSize, Files.size(gz)));
        c.that(Files.exists(ArticleStore.blocksFile(gz)) && Files.readAllLines(ArticleStore.blocksFile(gz)).size() > 3, "in blocks, so one article can be read without unpacking the month");
        ArticleStore again = new ArticleStore(dir);
        again.load();
        c.eq(before + 3000, again.size(), "after a restart every article is still there");
        c.that(again.since(oldTime - DAY).stream().filter(a -> a.id.startsWith("o")).count() == 3000, "older months load from the compressed file when needed");
        c.eq(scan(again, new SearchQuery("hamburg")), find(again, new SearchQuery("hamburg")), "search over a compressed month: index and scan agree");
        c.eq(429, find(again, new SearchQuery("hamburg")).size(), "(429 Hamburg articles)");
        again.index().refresh();
        c.eq(scan(again, new SearchQuery("hamburg cranes")), find(again, new SearchQuery("hamburg cranes")), "… also once the compressed month is indexed");
        Files.writeString(ArticleStore.blocksFile(gz), "0\t0\n5\t999999999\n");
        c.eq(429, find(again, new SearchQuery("hamburg")).size(), "a damaged block table is ignored (the month is unpacked whole)");
        Files.delete(ArticleStore.blocksFile(gz));
        c.eq(429, find(again, new SearchQuery("hamburg")).size(), "… and so is a missing one");
        c.that(again.compressOlderThan(6).isEmpty(), "compressing again does nothing");
        // An old month written to again (e.g. by an older copy of the app) keeps both parts.
        again.addAll(List.of(art("late", "Late article for an old month about Hamburg", "", "Old Gazette", oldTime + 5000_000L)));
        c.that(Files.exists(oldFile) && Files.exists(gz), "(an article for a compressed month lands in a new .tsv next to it)");
        ArticleStore third = new ArticleStore(dir);
        third.load();
        c.that(third.get("late") == null && third.size() == before + 3001, "both are read");
        third.compressOlderThan(6);
        ArticleStore fourth = new ArticleStore(dir);
        fourth.load();
        c.that(!Files.exists(oldFile) && fourth.size() == before + 3001, "compressing merges them: nothing lost");
        c.eq(beforeIds, scan(fourth, new SearchQuery("the OR a OR in OR of")).stream().filter(beforeIds::contains).collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                "every earlier article is still found");

        // ------------------------------------------------------------------ backup and restore
        c.suite("Backup and restore (STO-F04)");
        Path home = dir.getParent();
        Files.writeString(home.resolve("feeds.txt"), "World | BBC | https://feeds.bbci.co.uk/news/world/rss.xml\n");
        Files.writeString(home.resolve("watchlist.txt"), "Fed | Fed | yes\n");
        Files.writeString(home.resolve("settings.properties"), "dailyTime=06:30\n");
        Files.createDirectories(home.resolve("logs"));
        Files.writeString(home.resolve("logs").resolve("errors.log"), "noise");
        Path folder = Files.createTempDirectory("ld-backups");
        Backup.Info b1 = Backup.create(home, folder, "1.6", 2);
        c.that(b1.articles() == fourth.size(), String.format("a backup holds every article (%,d)", b1.articles()));
        Set<String> names = new TreeSet<>();
        try (var z = new java.util.zip.ZipFile(b1.file().toFile())) { z.stream().forEach(e -> names.add(e.getName())); }
        c.that(names.contains("feeds.txt") && names.contains("watchlist.txt") && names.contains("settings.properties") && names.stream().anyMatch(n -> n.endsWith(".tsv.gz")),
                "… and your feeds, watchlist, settings and compressed months");
        c.that(names.stream().noneMatch(n -> n.startsWith("logs/") || n.startsWith("data/index/") || n.endsWith(".lock")), "logs, the search index and lock files are left out");
        Thread.sleep(1100);
        Backup.create(home, folder, "1.6", 2);
        Thread.sleep(1100);
        Backup.create(home, folder, "1.6", 2);
        c.eq(2, Backup.list(folder).size(), "only the newest backups are kept (2 here)");

        Path pc2 = Files.createTempDirectory("ld-pc2");
        Files.createDirectories(pc2.resolve("data"));
        Files.writeString(pc2.resolve("feeds.txt"), "the second PC's own feeds\n");
        Files.writeString(pc2.resolve("data").resolve("articles-2020-01.tsv"), "x\n");
        Path aside = Backup.restore(Backup.list(folder).get(0), pc2);
        ArticleStore restored = new ArticleStore(pc2.resolve("data"));
        restored.load();
        c.eq(fourth.size(), restored.size(), "restoring on a second PC reproduces the archive");
        Set<String> a1 = new TreeSet<>(), a2 = new TreeSet<>();
        fourth.scan(a -> true, a -> { synchronized (a1) { a1.add(a.id + a.title + a.url); } });
        restored.scan(a -> true, a -> { synchronized (a2) { a2.add(a.id + a.title + a.url); } });
        c.that(a1.equals(a2), "… article for article");
        c.that(Files.readString(pc2.resolve("watchlist.txt")).equals("Fed | Fed | yes\n"), "your watchlist and settings come along");
        c.that(aside != null && Files.readString(aside.resolve("feeds.txt")).startsWith("the second PC's") && Files.exists(aside.resolve("data").resolve("articles-2020-01.tsv")),
                "what was there before is moved aside, not deleted");
        c.that(!Files.exists(pc2.resolve("data").resolve("articles-2020-01.tsv")), "(so the archive is exactly the backup's)");

        Path bad = folder.resolve("LinkedDesk-backup-bad.zip");
        Files.write(bad, Arrays.copyOf(Files.readAllBytes(Backup.list(folder).get(0)), 2000));
        try { Backup.restore(bad, pc2); c.that(false, "a damaged backup is refused"); }
        catch (java.io.IOException e) {
            ArticleStore check = new ArticleStore(pc2.resolve("data"));
            check.load();
            c.that(restored.size() == check.size(), "a damaged backup is refused before anything is touched");
        }
        Files.delete(bad);
        Path evil = folder.resolve("LinkedDesk-backup-evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../escaped.txt")); z.write("x".getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("backup.txt")); z.write("created=now\nversion=1.6\narticles=0\n".getBytes()); z.closeEntry();
        }
        Backup.restore(evil, pc2);
        c.that(!Files.exists(pc2.getParent().resolve("escaped.txt")), "a backup can't write outside the data folder");
    }
}
