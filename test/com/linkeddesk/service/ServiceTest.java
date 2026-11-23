package com.linkeddesk.service;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.app.Logs;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.TopicHits;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end on a temporary data folder: golden grouping/linking on the sample feeds (QA-F01),
 * custom topics (FET-F08), archive and live search (UI-F13), one download at a time (OPS-F04), logs (OPS-F05).
 */
public final class ServiceTest {
    static NewsService service;

    public static void run(Check c) throws Exception {
        Path home = Files.createTempDirectory("ld-service");
        System.setProperty("linkeddesk.home", home.toString());
        Path res = Path.of("test/resources").toAbsolutePath();
        StringBuilder feeds = new StringBuilder();
        String[][] list = {{"World", "BBC World", "bbc_world.xml"}, {"World", "Al Jazeera", "aljazeera.xml"}, {"Finance", "CNBC", "cnbc.xml"},
                {"Politics", "Politico", "politico.atom"}, {"Politics", "NPR Politics", "npr_politics.xml"}, {"Society", "Deutsche Welle", "dw.rdf"},
                {"World", "Google News World", "gnews.xml"}};
        for (String[] f : list) feeds.append(f[0]).append(" | ").append(f[1]).append(" | ").append(res.resolve(f[2]).toUri()).append('\n');
        Files.writeString(home.resolve(".feeds-v1.1"), "test: no v1.1 feed upgrade"); Files.writeString(home.resolve(".feeds-v1.8"), "test: no v1.1 feed upgrade");   // keep the test offline
        FeedCatalog.saveRawText(feeds.toString());

        // Local stand-in for Google News / Bing News search feeds.
        HttpServer search = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        search.createContext("/", ex -> {
            String q = URLDecoder.decode(String.valueOf(ex.getRequestURI().getRawQuery()).replaceAll(".*q=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            boolean google = ex.getRequestURI().getPath().startsWith("/rss/search");
            String body;
            if (q.toLowerCase().contains("hypersonic")) {
                body = google
                        ? "<rss><channel><item><title>Army tests hypersonic glide vehicle at Cape Canaveral - Defense News</title><link>https://news.google.com/rss/articles/h1</link><pubDate>" + rfc(0) + "</pubDate><source url='https://defensenews.com'>Defense News</source></item>"
                          + "<item><title>Hypersonic racing game tops charts - GameSpot</title><link>https://news.google.com/rss/articles/g1</link><pubDate>" + rfc(1) + "</pubDate><source url='x'>GameSpot</source></item>"
                          + "<item><title>Redstone Arsenal leads new missile program - AL.com</title><link>https://news.google.com/rss/articles/h2</link><pubDate>" + rfc(1) + "</pubDate><source url='https://al.com'>AL.com</source></item></channel></rss>"
                        : "<rss xmlns:News='urn:bing'><channel><item><title>Army tests hypersonic glide vehicle at Cape Canaveral</title><link>http://www.bing.com/news/apiclick.aspx?url=https%3a%2f%2fwww.defensenews.com%2fh1x&amp;c=1</link><pubDate>" + rfc(0) + "</pubDate><News:Source>Breaking Defense</News:Source></item></channel></rss>";
            } else {
                body = "<rss><channel></channel></rss>";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        search.start();
        String base = "http://127.0.0.1:" + search.getAddress().getPort();
        System.setProperty("linkeddesk.googleNewsBase", base);
        System.setProperty("linkeddesk.bingNewsBase", base);

        try {
            service = new NewsService(new ArticleStore(home.resolve("data")), Settings.load(),
                    new FeedHealth(home.resolve("feed-health.tsv")), new TopicHits(home.resolve("data/topic-hits.tsv")));
            service.loadAll();

            c.suite("Golden grouping and linking on the sample feeds (QA-F01)");
            FetchReport r = service.fetchNow(null, () -> false);
            c.eq(29, r.totalAdded, "29 sample articles saved");
            Analyzer.Result all = service.analyze(0);
            c.eq(20, all.stories().size(), "grouped into 20 stories");
            StoryCluster summit = find(all, "Trump, Xi and Putin");
            c.eq(3, summit.sourceCount(), "summit story has 3 sources (BBC, Al Jazeera, AP)");
            StoryCluster impeach = find(all, "House shelves Hegseth");
            c.that(impeach.links.stream().anyMatch(l -> l.to().headline.contains("Caracas")), "Hegseth impeachment links to the Caracas visit");
            c.that(all.threads().stream().anyMatch(t -> t.topic().contains("Nvidia")), "Nvidia thread found");
            c.that(find(all, "Ukrainian drones") != find(all, "Abu Dhabi"), "refinery strikes and peace talks stay separate stories");

            c.suite("Custom topics (FET-F08)");
            Topic hyper = new Topic(Category.WORLD, "Hypersonic weapons", "hypersonic OR \"glide vehicle\" -game", "en-US", false);
            TopicCatalog.save(List.of(hyper, new Topic(Category.FINANCE, "Paused one", "anything", "en-GB", true)));
            List<Topic> back = TopicCatalog.load();
            c.eq(2, back.size(), "topics.txt saved and read back");
            c.that(back.get(1).paused() && back.get(1).region().equals("en-GB"), "paused flag and region kept");
            c.that(hyper.feeds().get(0).url().contains("q=hypersonic+OR+%22glide+vehicle%22+-game"), "search words passed through, quoted phrase and exclusion included");
            c.eq("There is already a topic called \"Hypersonic weapons\".", TopicCatalog.validate("hypersonic weapons", "x", back), "duplicate topic names refused");
            FetchReport tr = service.fetchNow(null, () -> false);
            long topicFeeds = tr.results.stream().filter(x -> x.feed().isTopic()).count();
            c.eq(3L, topicFeeds, "daily download runs the active topic's 3 search feeds (Google + Bing once per OR alternative); the paused topic is skipped");
            List<String> urls = hyper.feeds().stream().map(x -> URLDecoder.decode(x.url(), StandardCharsets.UTF_8)).toList();
            c.that(urls.get(1).contains("q=hypersonic&") && urls.get(2).contains("q=\"glide vehicle\"&") && urls.stream().skip(1).noneMatch(u -> u.contains("-game") || u.contains(" OR ")),
                    "Bing gets one search per OR alternative, without -words (Bing returns nothing for OR)");
            c.that(urls.get(0).contains("hypersonic OR \"glide vehicle\" -game"), "Google gets the search words as written");
            c.that(service.search(new SearchQuery("gamespot"), NewsService.SearchFilter.ANY).total() == 0, "-game applied after download: the racing-game article was not saved");
            c.eq(3, service.topicHits().articles("Hypersonic weapons").size(), "topic remembers the 3 articles it found");
            Analyzer.Result topicView = service.analyzeTopic("Hypersonic weapons", 0);
            c.eq(2, topicView.stories().size(), "topic view groups them into 2 stories (Google + Bing report of the same test joined)");
            c.that(topicView.stories().stream().anyMatch(s -> s.sources().contains("Breaking Defense") && s.sources().contains("Defense News")),
                    "Bing and Google outlets both credited");
            Analyzer.Result main = service.analyze(0);
            c.that(main.stories().stream().anyMatch(s -> s.headline.contains("hypersonic")), "topic stories also appear in the main Stories view");
            FetchReport again = service.fetchNow(null, () -> false);
            c.eq(0, again.totalAdded, "running again adds nothing new");
            TopicCatalog.save(List.of());
            c.eq(3, service.topicHits().articles("Hypersonic weapons").size(), "deleting a topic keeps the articles it found");

            c.suite("Search (UI-F13)");
            NewsService.SearchResult sr = service.search(new SearchQuery("nvidia"), NewsService.SearchFilter.ANY);
            c.eq(4, sr.total(), "'nvidia' finds the 4 Nvidia articles");
            c.eq(3, service.search(new SearchQuery("\"strait of hormuz\""), NewsService.SearchFilter.ANY).total(), "exact phrase");
            c.eq(2, service.search(new SearchQuery("hegseth -caracas"), NewsService.SearchFilter.ANY).total(), "leave out a word");
            c.eq(2, service.search(new SearchQuery("anthropic"), NewsService.SearchFilter.ANY).total(), "'anthropic' alone: 2");
            c.eq(3, service.search(new SearchQuery("huang"), NewsService.SearchFilter.ANY).total(), "'huang' alone: 3");
            c.eq(1, service.search(new SearchQuery("anthropic huang"), NewsService.SearchFilter.ANY).total(), "both words: 1");
            c.eq(4, service.search(new SearchQuery("anthropic OR huang"), NewsService.SearchFilter.ANY).total(), "either word (OR): 4");
            c.eq(1, service.search(new SearchQuery("rodriguez"), NewsService.SearchFilter.ANY).total(), "accents ignored: 'rodriguez' finds 'Rodríguez'");
            c.eq(0, service.search(new SearchQuery("ran"), NewsService.SearchFilter.ANY).total(), "words match at word starts ('ran' doesn't match 'Iran')");
            c.eq(1, service.search(new SearchQuery("nvidia"), new NewsService.SearchFilter(0, 0, null, "fox")).total(), "source filter");
            c.eq(0, service.search(new SearchQuery("nvidia"), new NewsService.SearchFilter(System.currentTimeMillis() + 7_200_000L, 0, null, null)).total(), "date filter");
            c.that(sr.stories().stories().size() >= 2, "results grouped into stories");
            String hl = new SearchQuery("nvidia").highlight("Nvidia beats earnings");
            c.eq("<b>Nvidia</b> beats earnings", hl, "matched words highlighted");

            NewsService.SearchResult live = service.liveSearch(new SearchQuery("hypersonic glide"), NewsService.SearchFilter.ANY, null, () -> false);
            c.that(live.total() >= 2, "live search returns fresh articles (" + live.total() + ")");
            NewsService.SearchResult none = service.liveSearch(new SearchQuery("zzqx nothing"), NewsService.SearchFilter.ANY, null, () -> false);
            c.eq(0, none.total(), "live search with no results returns none");

            c.suite("One download at a time (OPS-F04)");
            CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    service.fetchNow(null, () -> { holding.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } return false; });
                } catch (Exception ignored) { }
            });
            holder.start();
            holding.await(5, TimeUnit.SECONDS);
            String msg = "";
            try { service.fetchNow(null, () -> false); } catch (NewsService.BusyException e) { msg = e.getMessage(); }
            release.countDown();
            holder.join();
            c.that(msg.startsWith("A download is already running"), "a second download in the same app is refused with a message");
            Process other = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                    "com.linkeddesk.service.ServiceTest$HoldLock", home.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String ready = new String(other.getInputStream().readNBytes(5), StandardCharsets.US_ASCII);
            String msg2 = "";
            try { service.fetchNow(null, () -> false); } catch (NewsService.BusyException e) { msg2 = e.getMessage(); }
            other.destroy();
            other.waitFor();
            c.that("ready".equals(ready) && msg2.startsWith("A download is already running"), "a download while the Windows task holds the lock is refused");
            c.eq(0, service.fetchNow(null, () -> false).totalAdded, "after the other process ends, downloads work again");

            c.suite("Logs (OPS-F05)");
            List<Logs.Run> runs = Logs.recentRuns(30);
            c.that(runs.size() >= 5, "every download is logged (" + runs.size() + " runs)");
            c.that(runs.get(runs.size() - 1).summary().startsWith("7 feeds checked, 29 items read, 29 new articles"), "first run's summary is in the log, listed last (newest first)");
            c.that(runs.get(0).who().startsWith("download") || runs.get(0).who().startsWith("live"), "newest run listed first");
            c.that(runs.stream().anyMatch(x -> x.who().startsWith("live search")), "live searches are logged too");
            c.that(runs.get(0).detail().contains("BBC World"), "per-feed detail kept for each run");
            Logs.error("Test error", new IllegalStateException("boom"));
            c.that(Logs.errorLogText().contains("java.lang.IllegalStateException: boom") && Logs.errorLogText().contains("at com.linkeddesk"), "errors logged with stack traces");
            Path elog = Logs.dir().resolve("errors.log");
            Files.write(elog, new byte[(int) (5L * 1024 * 1024 + 10)], StandardOpenOption.APPEND);
            Logs.error("After big log", null);
            c.that(Files.exists(Logs.dir().resolve("errors.1.log")) && Files.size(elog) < 10_000, "error log rolls over at 5 MB");
            c.that(Files.exists(Logs.dir().resolve("fetch-" + java.time.YearMonth.now() + ".log")), "download log is one file per month");

            c.suite("Corrections through the app (ANL-F04/F06/F08/F09/F10)");
            service.options();
            c.that(Files.exists(home.resolve("aliases.txt")) && Files.exists(home.resolve("stopwords.txt")), "aliases.txt and stopwords.txt appear next to feeds.txt");
            Analyzer.Result a0 = service.analyze(0);
            StoryCluster nv = find(a0, "Nvidia shares plunge"), fedS = find(a0, "Fed officials");
            service.judgeLink(nv, fedS, true, List.of());
            c.that(Files.readString(home.resolve("link-feedback.tsv")).contains("RIGHT"), "a verdict is saved to link-feedback.tsv");
            Analyzer.Result a1 = service.analyze(0);
            c.that(find(a1, "Nvidia shares plunge").links.stream().anyMatch(l -> l.confirmed() && l.to().headline.startsWith("Fed officials")),
                    "the view shows the link you confirmed straight away");
            StoryCluster nv1 = find(a1, "Nvidia shares plunge");
            service.removeVerdict(service.options().feedback().find(nv1, find(a1, "Fed officials")));
            c.that(find(service.analyze(0), "Nvidia shares plunge").links.stream().noneMatch(l -> l.to().headline.startsWith("Fed officials")), "undo removes it again");
            service.addDeskRule(new com.linkeddesk.analysis.DeskRules.Rule(com.linkeddesk.analysis.DeskRules.Kind.SOURCE, "Deutsche Welle", Category.WORLD, null, "Deutsche Welle"));
            c.that(service.analyze(0).stories().stream().filter(x -> x.sources().equals(java.util.Set.of("Deutsche Welle"))).allMatch(x -> x.category == Category.WORLD),
                    "a desk rule saved in desk-rules.tsv applies to the view");
            Files.writeString(home.resolve("aliases.txt"), Files.readString(home.resolve("aliases.txt")) + "\nPete Hegseth = Secretary Hegseth, War Secretary\n");
            Files.setLastModifiedTime(home.resolve("aliases.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
            c.that(service.options().lexicon().canonical(com.linkeddesk.analysis.Lexicon.key("War Secretary")) != null, "editing aliases.txt by hand applies without restarting");
            service.addStopWords(List.of("Hegseth"));
            StoryCluster hegseth = find(service.analyze(0), "House votes to effectively end");
            c.that(hegseth.links.stream().noneMatch(l -> l.to().headline.contains("Caracas")), "adding 'Hegseth' to the stop words removes the Hegseth link");
            com.linkeddesk.analysis.Thresholds tuned = com.linkeddesk.analysis.Thresholds.defaults().withLink(Category.TECH, 0.2);
            service.saveThresholds(tuned);
            c.eq(tuned, service.options().thresholds(), "calibrated settings saved to calibration.properties are used");
            com.linkeddesk.analysis.Calibrator.Suggestion sug = service.calibrate(null, () -> false);
            c.that(sug.sample().meetsTargets(), "Calibrate linking runs from the app and meets the targets");
        } finally {
            search.stop(0);
            System.clearProperty("linkeddesk.googleNewsBase");
            System.clearProperty("linkeddesk.bingNewsBase");
        }
    }

    private static String rfc(int hoursAgo) {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusHours(hoursAgo));
    }

    private static StoryCluster find(Analyzer.Result r, String start) {
        return r.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.title.startsWith(start))).findFirst().orElseThrow();
    }

    /** Child process standing in for the Windows task: holds the download lock until killed. */
    public static final class HoldLock {
        public static void main(String[] a) throws Exception {
            try (FileChannel ch = FileChannel.open(Path.of(a[0]).resolve(".download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock l = ch.lock()) {
                System.out.print("ready");
                System.out.flush();
                Thread.sleep(60_000);
            }
        }
    }
}
