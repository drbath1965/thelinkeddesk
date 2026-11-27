package com.linkeddesk.service;

import com.linkeddesk.Check;
import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.DownloadVolume;
import com.linkeddesk.feeds.DownloadVolume.Level;
import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FeedPacks;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.feeds.FillIn;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.schedule.DailyScheduler;
import com.linkeddesk.store.ArticleStore;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P13 (v1.13): more news — how much is downloaded, feed packs, filling in past days. */
public final class P13Test {
    static final long H = 3_600_000L, DAY = 24 * H;
    static final DateTimeFormatter RFC = DateTimeFormatter.RFC_1123_DATE_TIME;

    static String rss(String title, List<String[]> items) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>" + title + "</title>");
        for (String[] i : items) sb.append("<item><title>").append(i[0]).append("</title><link>").append(i[1])
                .append("</link><description>").append(i[0]).append(" in detail.</description><pubDate>").append(i[2]).append("</pubDate></item>");
        return sb.append("</channel></rss>").toString();
    }

    static String date(long ms) { return RFC.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)); }

    static Article art(String id, long published) {
        return new Article(id, "https://ex.com/" + id, "Headline " + id, "", "Ex", Category.WORLD, published, published);
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();

        // ------------------------------------------------------------------ FET-F10 levels and estimates
        c.suite("How much news: levels and estimates");
        DownloadVolume.Plan std = DownloadVolume.of(Level.STANDARD, 0, 0, 0, 1);
        DownloadVolume.Plan more = DownloadVolume.of(Level.MORE, 0, 0, 0, 1);
        DownloadVolume.Plan max = DownloadVolume.of(Level.MAXIMUM, 0, 0, 0, 1);
        DownloadVolume.Plan light = DownloadVolume.of(Level.LIGHT, 0, 0, 0, 1);
        c.eq(1, std.downloadsPerDay(), "Standard downloads once a day");
        c.eq(6, more.downloadsPerDay(), "More downloads every 4 hours (6 a day)");
        c.eq(24, max.downloadsPerDay(), "Maximum downloads every hour");
        c.that(light.maxPerFeed() == 15 && light.maxPerDay() == 600, "Light caps 15 per feed and 600 a day");
        DownloadVolume.Plan custom = DownloadVolume.of(Level.CUSTOM, 6, 40, 3000, 2);
        c.that(custom.everyHours() == 6 && custom.maxPerFeed() == 40 && custom.maxPerDay() == 3000 && custom.topicDepth() == 2, "Custom uses your own numbers");
        c.eq("every 4 hours", more.schedule(), "the schedule is described in words");
        c.eq(Level.STANDARD, Level.parse("nonsense"), "an unknown saved level falls back to Standard");
        // Like the real feed list: busy wire feeds list 20-50 but publish 100+ a day; quiet ones publish fewer than they list.
        List<DownloadVolume.FeedStat> feeds = new ArrayList<>();
        for (int i = 0; i < 40; i++) feeds.add(new DownloadVolume.FeedStat(30, 120, false));
        for (int i = 0; i < 30; i++) feeds.add(new DownloadVolume.FeedStat(25, 10, false));
        for (int i = 0; i < 10; i++) feeds.add(new DownloadVolume.FeedStat(100, 60, true));
        var eStd = DownloadVolume.estimate(std, feeds, 10, 1100);
        var eMore = DownloadVolume.estimate(more, feeds, 10, 1100);
        var eMax = DownloadVolume.estimate(max, feeds, 10, 1100);
        var eLight = DownloadVolume.estimate(light, feeds, 10, 1100);
        c.that(eStd.perDay() > 1000 && eStd.perDay() < 3000, "Standard estimates a realistic day (" + eStd.perDay() + ")");
        c.that(eMore.perDay() >= 2 * eStd.perDay(), "More at least doubles the new articles a day (" + eStd.perDay() + " → " + eMore.perDay() + ")");
        c.that(eMax.perDay() > eMore.perDay(), "Maximum gets more again (" + eMax.perDay() + ")");
        c.eq(600, eLight.perDay(), "Light stops at its daily limit");
        c.that(eStd.bytesPerYear() == Math.round(eStd.perDay() * 365.0 * 1100), "disk a year = articles a day × 365 × size per article");
        c.that(eMore.size().endsWith("GB") || eMore.size().endsWith("MB"), "disk size is shown as MB or GB (" + eMore.size() + ")");
        c.eq(1, DownloadVolume.editions(1), "depth 1 searches one Google News edition");
        c.eq(5, DownloadVolume.editions(3), "depth 3 searches five");

        Topic t = new Topic(Category.WORLD, "Iran war", "Iran war OR Iran strikes", "en-US", false);
        int base = t.feeds().size();
        c.eq(base, t.feeds(1).size(), "depth 1 searches topics as before");
        c.eq(base + 1, t.feeds(2).size(), "depth 2 adds the UK edition");
        c.eq(base + 4, t.feeds(3).size(), "depth 3 adds UK, India, Australia and Canada");
        c.that(t.feeds(3).stream().anyMatch(f -> f.url().contains("gl=IN") && f.isTopic() && f.topic().equals("Iran war")), "the extra editions still count as the topic's searches");
        Topic de = new Topic(Category.WORLD, "Wahl", "Bundestag", "de-DE", false);
        c.eq(de.feeds().size(), de.feeds(3).size(), "non-English topics aren't searched in English editions");
        Topic gb = new Topic(Category.WORLD, "NHS", "NHS", "en-GB", false);
        c.that(gb.feeds(2).stream().noneMatch(f -> f.name().contains("(UK)")), "a UK topic doesn't search the UK edition twice");

        // ------------------------------------------------------------------ limits
        c.suite("How much news: limits");
        List<Article> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) items.add(art("L" + i, now - i * H));
        Set<String> saved = Set.of("L0", "L1");
        List<Article> trimmed = DownloadVolume.limitPerFeed(items, a -> !saved.contains(a.id), 3);
        c.eq(5, trimmed.size(), "per feed: the 2 already saved plus the 3 newest new ones");
        c.that(trimmed.stream().map(a -> a.id).toList().containsAll(List.of("L2", "L3", "L4")), "the newest new articles are kept");
        c.eq(10, DownloadVolume.limitPerFeed(items, a -> true, 0).size(), "0 means no per-feed limit");
        int[] allow = DownloadVolume.allocate(new int[]{50, 30, 40}, new double[]{1.0, 10.0, 0.5}, 70);
        c.that(allow[1] == 30 && allow[0] == 40 && allow[2] == 0, "the daily allowance goes to topics first, then by outlet weight; least important skipped");
        LocalDate today = LocalDate.now();
        DownloadVolume.Plan cap = DownloadVolume.of(Level.CUSTOM, 0, 0, 100, 1);
        c.eq(40, DownloadVolume.remainingToday(cap, today, 60, today), "60 kept today leaves 40 of 100");
        c.eq(100, DownloadVolume.remainingToday(cap, today.minusDays(1), 60, today), "the count starts again each day");
        c.eq(Integer.MAX_VALUE, DownloadVolume.remainingToday(std, today, 5000, today), "no daily limit, no cap");

        // ------------------------------------------------------------------ feed rates
        c.suite("How much news: feed rates and history");
        Path tmp = Files.createTempDirectory("ld-p13");
        FeedHealth fh = new FeedHealth(tmp.resolve("health.tsv"));
        FeedSource busy = new FeedSource(Category.WORLD, "Busy", "https://busy.example/rss");
        List<Article> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) twenty.add(art("B" + i, now - i * 24 * 60_000L));   // 20 items over 8 hours
        fh.recordListing(busy, twenty, now);
        double rate = fh.get(busy.url()).publishedPerDay;
        c.that(rate > 50 && rate < 70, "20 items over 8 hours ≈ 60 a day (" + Math.round(rate) + ")");
        c.eq(20, fh.get(busy.url()).itemsListed, "the number of items listed is kept");
        fh.recordSuccess(busy, 7, false, "", "", now);
        fh.save();
        FeedHealth back = new FeedHealth(tmp.resolve("health.tsv")).load();
        c.that(back.get(busy.url()).itemsListed == 20 && Math.abs(back.get(busy.url()).publishedPerDay - rate) < 0.2, "rate and items listed survive saving");
        Files.writeString(tmp.resolve("old.tsv"), "# old\nhttps://o.example/rss\tOld\t" + now + "\t0\t\t0\t0\t\t\t" + now + "\t" + today + ":5\n");
        FeedHealth old = new FeedHealth(tmp.resolve("old.tsv")).load();
        c.that(old.get("https://o.example/rss").itemsListed == 0 && old.get("https://o.example/rss").addedPerDay.get(today) == 5, "feed-health files from 1.12 still load");

        // ------------------------------------------------------------------ scheduling
        c.suite("How much news: every few hours");
        ZoneId z = ZoneId.of("America/Chicago");
        Instant at10 = LocalDateTime.of(2026, 11, 27, 10, 0).atZone(z).toInstant();
        Instant fetched6 = LocalDateTime.of(2026, 11, 27, 6, 0).atZone(z).toInstant();
        Instant never = Instant.EPOCH;
        c.that(!DailyScheduler.isDue(at10, z, true, LocalTime.of(6, 0), fetched6, never), "once a day: not due again at 10:00 after 06:00");
        c.that(DailyScheduler.isDue(at10, z, true, LocalTime.of(6, 0), fetched6, never, 4), "every 4 hours: due at 10:00 after 06:00");
        c.that(!DailyScheduler.isDue(at10, z, true, LocalTime.of(6, 0), fetched6.plus(Duration.ofHours(2)), never, 4), "every 4 hours: not due 2 hours after the last");
        c.that(!DailyScheduler.isDue(at10, z, false, LocalTime.of(6, 0), fetched6, never, 4), "switched off: never due");
        c.that(!DailyScheduler.isDue(at10, z, true, LocalTime.of(6, 0), fetched6, at10.minusSeconds(300), 1), "a failed try isn't repeated straight away");
        Path tx = tmp.resolve("WindowsTaskXml.txt");
        String xml1 = taskXml(LocalTime.of(6, 30), 1);
        String xml0 = taskXml(LocalTime.of(6, 30), 0);
        c.that(xml1.contains("<Interval>PT1H</Interval>") && xml1.contains("<Duration>P1D</Duration>"), "hourly: the Windows task repeats every hour all day");
        c.that(xml1.indexOf("<Repetition>") > xml1.indexOf("<CalendarTrigger>") && xml1.indexOf("<Repetition>") < xml1.indexOf("</CalendarTrigger>"), "the repetition sits inside the daily trigger");
        c.that(!xml0.contains("<Repetition>"), "once a day: no repetition, as before");
        c.that(xml1.contains("every 1 hour."), "the task description says how often");
        Files.writeString(tx, xml1);

        // ------------------------------------------------------------------ FET-F11 packs (pure)
        c.suite("Feed packs");
        List<FeedPacks.Pack> packs = FeedPacks.all();
        c.that(packs.size() >= 14, "regional, desk and Google News country packs are offered (" + packs.size() + ")");
        c.that(packs.stream().anyMatch(p -> p.id().equals("google-gb")) && packs.stream().anyMatch(p -> p.id().equals("google-in")), "Google News UK and India editions are packs");
        c.that(packs.stream().allMatch(p -> p.feeds().size() >= 4), "every pack has at least 4 feeds");
        Set<String> ids = new HashSet<>();
        c.that(packs.stream().allMatch(p -> ids.add(p.id())), "pack ids are unique");
        c.that(packs.stream().flatMap(p -> p.feeds().stream()).noneMatch(f -> FeedCatalog.parse(FeedCatalog.DEFAULT_FEEDS).stream().anyMatch(d -> d.url().equals(f.url()))),
                "no pack repeats a feed from the default list");
        FeedPacks.Pack eu = FeedPacks.find("europe");
        String text = "# feeds\nWorld | BBC | https://bbc.example/rss\n" + "Politics | Euractiv | " + eu.feeds().get(0).url() + "\n";
        List<FeedSource> missing = FeedPacks.missing(eu, FeedCatalog.parse(text));
        c.eq(eu.feeds().size() - 1, missing.size(), "feeds you already have are left out of the pack");
        String withEu = FeedPacks.withPack(text, eu, missing);
        c.that(FeedPacks.added(withEu).contains("europe"), "the pack is recorded as added");
        c.eq(1 + 1 + missing.size(), FeedCatalog.parse(withEu).size(), "its feeds are in the list");
        c.that(FeedCatalog.parse(withEu).stream().anyMatch(f -> f.name().equals("Euractiv") && f.category() == Category.POLITICS), "each feed keeps its desk");
        c.eq(withEu, FeedPacks.withPack(withEu, eu, missing), "adding twice changes nothing");
        String without = FeedPacks.withoutPack(withEu + "\nWorld | Later | https://later.example/rss\n", eu);
        c.that(!FeedPacks.added(without).contains("europe") && FeedCatalog.parse(without).size() == 3, "removing takes out the whole block in one go, and nothing else");
        c.that(without.contains("Later") && without.contains("BBC"), "feeds before and after the pack stay");

        // ------------------------------------------------------------------ FET-F12 fill in (pure)
        c.suite("Fill in past days");
        LocalDate d0 = LocalDate.of(2026, 11, 27);
        c.eq(List.of(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26)), FillIn.days(d0, 3), "the last 3 days, oldest first, not today");
        c.eq(30, FillIn.days(d0, 99).size(), "at most 30 days");
        c.eq(List.of(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26)), FillIn.missedDays(LocalDate.of(2026, 11, 24), d0, 7), "off from the 24th: the 25th and 26th were missed");
        c.that(FillIn.missedDays(LocalDate.of(2026, 11, 26), d0, 7).isEmpty(), "a download yesterday: nothing missed");
        c.eq(7, FillIn.missedDays(LocalDate.of(2026, 10, 1), d0, 7).size(), "a long absence fills in the last 7 days automatically");
        List<FeedSource> ff = FillIn.feeds(List.of(t), List.of(Category.WORLD, Category.FINANCE), FillIn.days(d0, 2));
        c.eq(6, ff.size(), "one search per topic and desk per day");
        String u = URLDecoder.decode(ff.get(0).url(), StandardCharsets.UTF_8);
        c.that(u.contains("after:2026-11-25") && u.contains("before:2026-11-26"), "each search is limited to its day");
        c.that(ff.get(0).isTopic() && ff.get(0).topic().equals("Iran war"), "topic searches still count for the topic");
        var fe = FillIn.estimate(7, 3, 6);
        c.that(fe.searches() == 63 && fe.articles() > 0 && fe.seconds() > 0, "the estimate counts searches, articles and time");

        // ------------------------------------------------------------------ against a local server
        c.suite("More news: downloads against a test server");
        AtomicLong clock = new AtomicLong(now);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A busy wire: publishes one item every 24 minutes (60 a day) and lists only the newest 20.
        server.createContext("/busy", ex -> {
            long tnow = clock.get();
            long slot = tnow / (24 * 60_000L);
            List<String[]> list = new ArrayList<>();
            for (int i = 0; i < 20; i++) { long s = slot - i; list.add(new String[]{"Wire item " + s, "https://wire.example/" + s, date(s * 24 * 60_000L)}); }
            send(ex, rss("Wire", list));
        });
        for (String n : new String[]{"a", "b", "c", "d"}) server.createContext("/feed-" + n, ex -> {
            List<String[]> list = new ArrayList<>();
            for (int i = 0; i < 30; i++) list.add(new String[]{"Story " + n + " " + i + " " + (clock.get() / DAY), "https://" + n + ".example/" + i + "-" + (clock.get() / DAY), date(clock.get() - i * H)});
            send(ex, rss(n, list));
        });
        server.createContext("/broken", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        // Google News: dated searches return 12 items from that day.
        server.createContext("/rss/search", ex -> {
            String q = URLDecoder.decode(ex.getRequestURI().getRawQuery().replaceAll(".*?q=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("after:(\\d{4}-\\d{2}-\\d{2})").matcher(q);
            List<String[]> list = new ArrayList<>();
            if (m.find()) {
                LocalDate d = LocalDate.parse(m.group(1));
                String what = q.substring(0, m.start()).trim();
                for (int i = 0; i < 12; i++) list.add(new String[]{what + " news " + d + " #" + i, "https://g.example/" + what.hashCode() + "/" + d + "/" + i,
                        date(d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() - i * 60_000L)});
            }
            send(ex, rss("Google", list));
        });
        server.start();
        String b = "http://127.0.0.1:" + server.getAddress().getPort();
        String oldHome = System.getProperty("linkeddesk.home"), oldG = System.getProperty("linkeddesk.googleNewsBase"), oldB = System.getProperty("linkeddesk.bingNewsBase");
        Path home = Files.createTempDirectory("ld-p13-home");
        System.setProperty("linkeddesk.home", home.toString());
        System.setProperty("linkeddesk.googleNewsBase", b);
        System.setProperty("linkeddesk.bingNewsBase", b + "/bing");
        try {
            Files.writeString(home.resolve(".feeds-v1.1"), "test");
            Files.writeString(home.resolve(".feeds-v1.8"), "test");
            Files.writeString(AppPaths.feedsFile(), "World | Wire | " + b + "/busy\nWorld | A | " + b + "/feed-a\nFinance | B | " + b + "/feed-b\n");
            TopicCatalog.save(List.of());
            Settings s = Settings.load();
            s.setFillInAuto(false);
            s.save();
            NewsService svc = new NewsService(new ArticleStore(home.resolve("data")), s);
            svc.loadAll();

            // Standard: once a day catches 20 of the wire's 60.
            FetchReport r1 = svc.fetchNow(null, () -> false);
            int wireStd = (int) r1.addedArticles.stream().filter(a -> a.url.startsWith("https://wire")).count();
            c.eq(20, wireStd, "once a day keeps only the 20 the busy feed lists");
            // A day later, every 4 hours: 6 downloads catch all 60.
            int wireMore = 0;
            for (int k = 1; k <= 6; k++) {
                clock.set(now + DAY + k * 4 * H);
                wireMore += (int) svc.fetchNow(null, () -> false).addedArticles.stream().filter(a -> a.url.startsWith("https://wire")).count();
            }
            c.that(wireMore >= 2 * wireStd, "downloading every 4 hours at least doubles what a busy feed gives (" + wireStd + " → " + wireMore + " a day)");
            c.that(svc.health().find(b + "/busy").publishedPerDay > 40, "the busy feed's rate is learned (" + Math.round(svc.health().find(b + "/busy").publishedPerDay) + " a day)");
            var est = svc.volumeEstimate(DownloadVolume.of(Level.MORE, 0, 0, 0, 1));
            var estStd = svc.volumeEstimate(DownloadVolume.of(Level.STANDARD, 0, 0, 0, 1));
            c.that(est.perDay() > estStd.perDay(), "the estimate for this PC's feeds rises with More (" + estStd.perDay() + " → " + est.perDay() + ")");

            // Per-day limit: stops and says so.
            s.reload(); s.setDownloadVolume("CUSTOM"); s.setDownloadEveryHours(0); s.setMaxPerFeed(0); s.setMaxPerDay(25); s.setTopicDepth(1);
            s.setCounted(LocalDate.now(), 0); s.save();
            clock.set(now + 3 * DAY);
            FetchReport lim = svc.fetchNow(null, () -> false);
            c.eq(25, lim.totalAdded, "a daily limit of 25 keeps 25 new articles");
            c.that(lim.dailyLimitReached && lim.skippedByLimit > 0, "the report says the limit was reached and how many were skipped");
            c.that(lim.summary().contains("today's limit of 25"), "the summary says so in words: " + lim.summary());
            clock.set(now + 3 * DAY + H);
            FetchReport lim2 = svc.fetchNow(null, () -> false);
            c.eq(0, lim2.totalAdded, "once the day's limit is used, later downloads that day add nothing");
            c.that(lim2.dailyLimitReached, "and say why");
            // Per-feed limit.
            s.reload(); s.setMaxPerDay(0); s.setMaxPerFeed(5); s.save();
            clock.set(now + 5 * DAY);
            FetchReport pf = svc.fetchNow(null, () -> false);
            c.that(pf.results.stream().allMatch(r -> r.added() <= 5), "at most 5 new articles per feed per download");
            c.that(pf.skippedByLimit > 0 && pf.summary().contains("per-feed limit"), "the summary mentions the per-feed limit");
            s.reload(); s.setDownloadVolume("STANDARD"); s.setMaxPerFeed(0); s.save();
            c.eq(DownloadVolume.of(Level.STANDARD, 0, 0, 0, 1), DownloadVolume.of(s), "the plan is read back from settings");

            var perDay = svc.downloadsPerDay(30);
            c.eq(30, perDay.size(), "download history covers 30 days");
            c.that(perDay.values().stream().mapToInt(Integer::intValue).sum() > 0, "and shows the articles downloaded");
            c.that(!svc.downloadsPerFeed(7).isEmpty() && svc.downloadsPerFeed(7).get(0).getValue() >= svc.downloadsPerFeed(7).get(svc.downloadsPerFeed(7).size() - 1).getValue(),
                    "per-feed history is busiest first");
            c.that(svc.claimDailyExtras() && !svc.claimDailyExtras(), "the once-a-day extras (briefing, Claude, email) run only after the first download of the day");

            // Feed packs: test before adding (works / fails), add, remove.
            FeedPacks.Pack fake = new FeedPacks.Pack("test", "Test pack", "for tests",
                    List.of(new FeedSource(Category.WORLD, "C", b + "/feed-c"), new FeedSource(Category.FINANCE, "D", b + "/feed-d"), new FeedSource(Category.WORLD, "Broken", b + "/broken")));
            List<NewsService.FeedTest> tests = svc.testFeeds(fake.feeds(), () -> false);
            c.that(tests.get(0).ok() && tests.get(0).items() == 30 && tests.get(0).perDay() > 0, "testing a pack shows each feed's articles and rate");
            c.that(!tests.get(2).ok(), "a broken feed shows as failed");
            c.that(svc.health().find(b + "/feed-c") == null, "testing doesn't touch feed health");
            List<FeedSource> working = tests.stream().filter(NewsService.FeedTest::ok).map(NewsService.FeedTest::feed).toList();
            FeedPacks.add(fake, working);
            c.that(FeedCatalog.load().stream().anyMatch(f -> f.name().equals("C")) && FeedCatalog.load().stream().noneMatch(f -> f.name().equals("Broken")),
                    "only the feeds that worked are added");
            c.that(FeedCatalog.rawText().contains("Pack: Test pack [test]"), "the pack is marked in the feed list as one block");
            FetchReport pr = svc.fetchFeeds(working, "feed pack", null, () -> false);
            c.that(pr.totalAdded >= 50, "the new feeds can be downloaded straight away (" + pr.totalAdded + ")");
            FeedPacks.remove(fake);
            c.that(FeedCatalog.load().stream().noneMatch(f -> f.name().equals("C") || f.name().equals("D")) && FeedCatalog.load().size() == 3,
                    "removing the pack takes out its feeds in one click and leaves yours");

            // Fill in past days.
            Topic iran = new Topic(Category.WORLD, "Iran war", "Iran war", "en-US", false);
            TopicCatalog.save(List.of(iran));
            List<LocalDate> days = FillIn.days(LocalDate.now(), 3);
            FetchReport fi = svc.fillIn(days, List.of(iran), List.of(Category.WORLD, Category.FINANCE), null, () -> false);
            c.eq(3 * 3 * 12, fi.totalAdded, "3 days × (1 topic + 2 desks) × 12 items are added");
            c.that(fi.addedArticles.stream().allMatch(a -> svc.isFilledIn(a.id)), "filled-in articles are marked");
            c.that(fi.addedArticles.stream().anyMatch(a -> LocalDate.ofInstant(Instant.ofEpochMilli(a.published), ZoneOffset.UTC).equals(days.get(0))), "articles come from the days filled in");
            c.that(svc.health().entries().stream().noneMatch(e -> e.url.contains("after")), "the dated searches don't fill feed health with one-off entries");
            FetchReport again = svc.fillIn(days, List.of(iran), List.of(Category.WORLD, Category.FINANCE), null, () -> false);
            c.eq(0, again.totalAdded, "filling in the same days again adds no duplicates");
            c.that(new com.linkeddesk.store.FilledIn(home.resolve("filled-in.txt")).size() == fi.totalAdded, "the marks are saved");
            c.that(!svc.isFilledIn(r1.addedArticles.get(0).id), "articles from normal downloads aren't marked");

            // Automatic after the PC was off.
            s.reload(); s.setFillInAuto(true);
            s.setLastFetch(System.currentTimeMillis() - 4 * DAY);
            s.save();
            FetchReport auto = svc.fetchNow(null, () -> false);
            c.eq(3, auto.filledDays, "off for 4 days: the 3 whole days missed are filled in");
            c.that(auto.filledIn > 0 && auto.summary().contains("missed days"), "the download report says so: " + auto.summary());
            s.reload(); s.setLastFetch(System.currentTimeMillis() - 2 * H); s.save();
            c.eq(0, svc.fetchNow(null, () -> false).filledDays, "nothing is filled in after a normal gap");
            s.reload(); s.setFillInAuto(false); s.setLastFetch(System.currentTimeMillis() - 4 * DAY); s.save();
            c.eq(0, svc.fetchNow(null, () -> false).filledDays, "switched off: nothing is filled in automatically");
        } finally {
            server.stop(0);
            restore("linkeddesk.home", oldHome);
            restore("linkeddesk.googleNewsBase", oldG);
            restore("linkeddesk.bingNewsBase", oldB);
        }
    }

    static String taskXml(LocalTime time, int every) throws Exception {
        var m = com.linkeddesk.schedule.WindowsTask.class.getDeclaredMethod("taskXml", LocalTime.class, com.linkeddesk.schedule.WindowsTask.Action.class, String.class, LocalDate.class, int.class);
        m.setAccessible(true);
        var a = new com.linkeddesk.schedule.WindowsTask.Action("javaw.exe", "-jar x.jar --fetch", "C:\\x");
        return (String) m.invoke(null, time, a, "PC\\david", LocalDate.of(2026, 11, 27), every);
    }

    static void restore(String k, String v) { if (v == null) System.clearProperty(k); else System.setProperty(k, v); }

    static void send(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/rss+xml");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
