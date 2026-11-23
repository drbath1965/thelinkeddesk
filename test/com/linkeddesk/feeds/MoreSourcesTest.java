package com.linkeddesk.feeds;

import com.linkeddesk.Check;
import com.linkeddesk.ai.ClaudeClient;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Language;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.app.Settings;
import com.linkeddesk.export.PhonePage;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.StorageTest;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Podcasts, YouTube and social feeds (FET-F06), full text from publishers' feeds (FET-F07), other languages
 * (ANL-F11) and the phone briefing (UI-F12).
 */
public final class MoreSourcesTest {
    static final FeedSource WORLD = new FeedSource(Category.WORLD, "Test", "https://example.org/feed");

    static List<Article> parse(String xml, String name) throws Exception {
        return FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), new FeedSource(Category.WORLD, name, "https://example.org/feed"), System.currentTimeMillis());
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        String rfc = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now().minusHours(2));
        String iso = java.time.Instant.ofEpochMilli(now - 2 * 3_600_000L).toString();

        // ------------------------------------------------------------------ FET-F06
        c.suite("Podcasts, YouTube and social feeds (FET-F06)");
        List<Article> yt = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
                 <title>DW News</title>
                 <entry><id>yt:video:abc123</id><yt:videoId>abc123</yt:videoId><title>Russian cargo ship hit by drones in the Baltic Sea</title>
                  <link rel="alternate" href="https://www.youtube.com/watch?v=abc123"/><published>%s</published>
                  <media:group><media:title>Russian cargo ship hit by drones</media:title><media:description>A Russian cargo ship was hit by drones in the Baltic Sea near Finland, officials said.</media:description></media:group>
                 </entry></feed>""".formatted(iso), "DW News (YouTube)");
        c.that(yt.size() == 1 && yt.get(0).kind.equals("video") && yt.get(0).url.equals("https://www.youtube.com/watch?v=abc123"), "a YouTube channel's videos are read, marked as video");
        c.that(yt.get(0).summary.startsWith("A Russian cargo ship was hit"), "with the video's description as its summary");
        List<Article> pod = parse("""
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Today in Focus</title>
                 <item><title>Why Belgium went on strike</title><itunes:summary>Belgium's unions shut airports and trains in a national strike against austerity.</itunes:summary>
                  <enclosure url="https://cdn.example.org/ep42.mp3" length="123" type="audio/mpeg"/><itunes:duration>27:14</itunes:duration><pubDate>%s</pubDate></item>
                </channel></rss>""".formatted(rfc), "Today in Focus");
        c.that(pod.size() == 1 && pod.get(0).kind.equals("audio") && pod.get(0).url.equals("https://cdn.example.org/ep42.mp3"), "a podcast episode without a web page links to its audio, marked as podcast");
        c.that(pod.get(0).summary.contains("national strike"), "with the episode's description");
        List<Article> masto = parse("""
                <rss version="2.0"><channel><title>Someone</title>
                 <item><link>https://mastodon.social/@reporter/1133557799</link><pubDate>%s</pubDate>
                  <description>&lt;p&gt;Breaking: Flights in Belgium cancelled on Monday due to a national strike. Airports in Brussels and Charleroi are closed.&lt;/p&gt;</description></item>
                </channel></rss>""".formatted(rfc), "Reporter (Mastodon)");
        c.that(masto.size() == 1 && masto.get(0).kind.equals("post"), "a Mastodon post (no headline) is read, marked as post");
        c.eq("Breaking: Flights in Belgium cancelled on Monday due to a national strike.", masto.get(0).title, "its first sentence becomes the headline");
        List<Article> bsky = parse("""
                <rss version="2.0"><channel><title>@reporter.bsky.social</title>
                 <item><link>https://bsky.app/profile/reporter.bsky.social/post/3kxyz</link><description>Ukraine says its naval drones sank a Russian warship near Libya, in what would be the war's farthest strike yet from Ukrainian shores so far this year and possibly ever</description><pubDate>%s</pubDate></item>
                </channel></rss>""".formatted(rfc), "Reporter (Bluesky)");
        c.that(bsky.size() == 1 && bsky.get(0).kind.equals("post") && bsky.get(0).title.length() <= 111 && bsky.get(0).title.endsWith("…"), "a long Bluesky post gets a short headline: " + bsky.get(0).title);
        List<Article> reddit = parse("""
                <feed xmlns="http://www.w3.org/2005/Atom"><title>worldnews</title>
                 <entry><title>Zelenskyy confirms Ukraine attacked Russian warship off Libya</title><link href="https://www.reddit.com/r/worldnews/comments/1abc/zelenskyy/"/>
                  <updated>%s</updated><content type="html">&lt;div&gt;Link to article&lt;/div&gt; submitted by &lt;a href="https://www.reddit.com/user/x"&gt; /u/x &lt;/a&gt;</content></entry></feed>""".formatted(iso), "r/worldnews");
        c.that(reddit.size() == 1 && reddit.get(0).kind.equals("post") && !reddit.get(0).summary.contains("submitted by"), "a subreddit's posts are read (without the \"submitted by\" line)");
        // Kept in the archive, readable by earlier versions
        Path dir = Files.createTempDirectory("ld-media").resolve("data");
        ArticleStore st = new ArticleStore(dir);
        st.load();
        List<Article> mix = new ArrayList<>(StorageTest.real(now - 3_600_000L));
        mix.addAll(yt); mix.addAll(pod); mix.addAll(masto);
        st.addAll(mix);
        ArticleStore st2 = new ArticleStore(dir);
        st2.load();
        c.that(st2.get(yt.get(0).id).kind.equals("video") && st2.get(pod.get(0).id).kind.equals("audio") && st2.get(masto.get(0).id).kind.equals("post"),
                "the kind is saved with the article (a 9th column only for these, so earlier versions still read the file)");
        c.eq("", ArticleStore.decode("id\thttps://x\tTitle\tSum\tSrc\tWORLD\t1\t2").kind, "ordinary lines read as articles");
        Analyzer.Result r = Analyzer.analyze(new ArrayList<>(st2.all()));
        StoryCluster withVideo = r.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.kind.equals("video"))).findFirst().get();
        c.that(withVideo.articles.size() > 1, "a video groups with the text coverage of the same event (" + withVideo.headline + ")");
        StoryCluster withPost = r.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.kind.equals("post"))).findFirst().get();
        c.that(withPost.articles.size() > 1, "so does a social post (" + withPost.headline + ")");

        // Links from the browser to feeds
        Map<String, String> pages = Map.of(
                "https://www.youtube.com/@dwnews", "<html><head><title>DW News - YouTube</title><meta property=\"og:title\" content=\"DW News\"></head><body>\"externalId\":\"UCknLrEdhRCp1aegoMqRaCZg\"</body>",
                "https://itunes.apple.com/lookup?id=1440133626&entity=podcast", "{\"resultCount\":1,\"results\":[{\"collectionName\":\"Today in Focus\",\"feedUrl\":\"https:\\/\\/www.theguardian.com\\/news\\/series\\/todayinfocus\\/podcast.xml\"}]}",
                "https://www.youtube.com/@nobody", "<html><title>Not found</title></html>");
        FeedLinks.Fetch fake = u -> { String p = pages.get(u); if (p == null) throw new java.io.IOException("no page " + u); return p; };
        c.eq("https://www.youtube.com/feeds/videos.xml?channel_id=UCknLrEdhRCp1aegoMqRaCZg", FeedLinks.resolve("https://www.youtube.com/@dwnews", fake).feedUrl(), "a YouTube @handle finds its channel's feed");
        c.eq("DW News", FeedLinks.resolve("youtube.com/@dwnews", fake).name(), "… and its name");
        c.eq("https://www.youtube.com/feeds/videos.xml?channel_id=UCknLrEdhRCp1aegoMqRaCZg", FeedLinks.resolve("https://www.youtube.com/channel/UCknLrEdhRCp1aegoMqRaCZg", fake).feedUrl(), "a channel link");
        c.eq("https://www.youtube.com/feeds/videos.xml?playlist_id=PLabc-123", FeedLinks.resolve("https://www.youtube.com/playlist?list=PLabc-123", fake).feedUrl(), "a playlist");
        c.eq("https://www.theguardian.com/news/series/todayinfocus/podcast.xml", FeedLinks.resolve("https://podcasts.apple.com/gb/podcast/today-in-focus/id1440133626", fake).feedUrl(), "an Apple Podcasts show finds its feed");
        c.eq("https://mastodon.social/@reporter.rss", FeedLinks.resolve("https://mastodon.social/@reporter", fake).feedUrl(), "a Mastodon account");
        c.eq("https://journa.host/@someone.rss", FeedLinks.resolve("https://mastodon.social/@someone@journa.host", fake).feedUrl(), "… also one on another server");
        c.eq("https://bsky.app/profile/reporter.bsky.social/rss", FeedLinks.resolve("bsky.app/profile/reporter.bsky.social", fake).feedUrl(), "a Bluesky account");
        c.eq("https://www.reddit.com/r/worldnews/.rss", FeedLinks.resolve("https://www.reddit.com/r/worldnews/", fake).feedUrl(), "a subreddit");
        c.eq("https://feeds.bbci.co.uk/news/world/rss.xml", FeedLinks.resolve("https://feeds.bbci.co.uk/news/world/rss.xml", fake).feedUrl(), "an ordinary feed address stays as it is");
        try { FeedLinks.resolve("https://www.youtube.com/@nobody", fake); c.that(false, "an unknown channel is explained"); }
        catch (java.io.IOException e) { c.that(e.getMessage().contains("channel ID"), "a channel that can't be found says how to get its ID"); }

        // ------------------------------------------------------------------ FET-F07
        c.suite("Full text from publishers' feeds (FET-F07)");
        String body = "<p>First paragraph about the harbour strike in Rotterdam, where dock workers walked out over pay.</p>" + "<p>"
                + "Second paragraph with details: the union said cranes would stay idle for 48 hours, and that the zebrafish sanctuary was unaffected. ".repeat(6) + "</p>"
                + "<script>track()</script><p>Third paragraph.</p>";
        List<Article> full = parse("""
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Port Blog</title>
                 <item><title>Rotterdam dock workers strike</title><link>https://portblog.example.org/strike</link><pubDate>%s</pubDate>
                  <description>Dock workers in Rotterdam walked out over pay.</description><content:encoded><![CDATA[%s]]></content:encoded></item>
                 <item><title>Short one</title><link>https://portblog.example.org/short</link><description>A short note.</description><content:encoded>&lt;p&gt;A short note.&lt;/p&gt;</content:encoded></item>
                </channel></rss>""".formatted(rfc, body), "Port Blog");
        Article fa = full.get(0);
        c.that(fa.fullText.startsWith("First paragraph about the harbour strike") && fa.fullText.contains("\n\nSecond paragraph") && fa.fullText.endsWith("Third paragraph."),
                "the full text in content:encoded is kept, paragraph by paragraph");
        c.that(!fa.fullText.contains("track()") && fa.summary.equals("Dock workers in Rotterdam walked out over pay."), "scripts are dropped; the summary stays the short one");
        c.that(full.get(1).fullText.isEmpty(), "a \"full text\" no longer than the summary isn't kept twice");
        st2.addAll(full);
        c.that(st2.get(fa.id).fullText.isEmpty(), "the copy in memory doesn't carry the text (it's kept in data\\fulltext)");
        ArticleStore st3 = new ArticleStore(dir);
        st3.load();
        c.that(st3.fullText(st3.get(fa.id)).equals(fa.fullText) && st3.hasFullText(st3.get(fa.id)) && !st3.hasFullText(st3.get(full.get(1).id)),
                "the story page can show it (\"Full text\")");

        // ------------------------------------------------------------------ ANL-F11 + service-level checks (stand-in Claude)
        c.suite("Other languages (ANL-F11)");
        Map<String, String> langs = new LinkedHashMap<>();
        langs.put("Ucrania afirma que derribó drones rusos sobre Kiev durante la noche", "es");
        langs.put("La Belgique paralysée par une grève nationale contre l'austérité", "fr");
        langs.put("Die Ukraine meldet einen Angriff auf ein russisches Kriegsschiff vor Libyen", "de");
        langs.put("Sciopero generale in Belgio: chiusi gli aeroporti di Bruxelles e Charleroi", "it");
        langs.put("Ucrânia diz que afundou navio de guerra russo na costa da Líbia", "pt");
        langs.put("Украина заявила об ударе по российскому кораблю у берегов Ливии", "ru");
        langs.put("Україна заявила про удар по російському кораблю біля Лівії", "uk");
        langs.put("أوكرانيا تعلن استهداف سفينة حربية روسية قبالة ليبيا", "ar");
        langs.put("乌克兰称在利比亚附近击中俄罗斯军舰", "zh");
        langs.put("ウクライナ、リビア沖でロシア軍艦を攻撃と発表", "ja");
        langs.put("우크라이나, 리비아 인근서 러시아 군함 공격", "ko");
        langs.put("Η Ουκρανία λέει ότι χτύπησε ρωσικό πολεμικό πλοίο", "el");
        langs.put("Zelenskyy says Ukraine behind drone attacks on Russian ships near Libya", "en");
        langs.put("Cathie Wood Sold Nvidia and Bought $78 Million of Anthropic at IPO", "en");
        langs.put("Polls Say Spain's Opposition Won TV Debate", "en");
        langs.put("Sánchez y Feijóo, cara a cara", "en");    // too short to tell: treated as English, not guessed
        int right = 0;
        StringBuilder wrong = new StringBuilder();
        for (var e : langs.entrySet()) { String got = Language.detect(e.getKey()); if (got.equals(e.getValue())) right++; else wrong.append(e.getKey(), 0, 20).append("→").append(got).append("; "); }
        c.that(right == langs.size(), "the language of a headline is recognised offline (" + right + " of " + langs.size() + ")" + (wrong.length() > 0 ? " — " + wrong : ""));

        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-lang");
        System.setProperty("linkeddesk.home", home.toString());
        Files.writeString(home.resolve(".feeds-v1.1"), "test");
        AtomicInteger calls = new AtomicInteger();
        Map<String, String[]> english = Map.of(
                "Ucrania afirma que hundió un buque de guerra ruso frente a Libia con drones navales",
                new String[]{"Ukraine says it sank a Russian warship off Libya with naval drones", "Zelensky said naval drones struck a Russian warship moored at a port in Libya."},
                "Huelga nacional en Bélgica: cancelados todos los vuelos del lunes",
                new String[]{"National strike in Belgium: all Monday flights cancelled", "Belgian airports will be shut on Monday because of a national strike against austerity."});
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            calls.incrementAndGet();
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            Matcher m = Pattern.compile("(\\d+) \\| \\w+ \\| ([^|]+?) \\| ").matcher(req.replace("\\n", "\n"));
            while (m.find()) {
                String[] en = english.get(m.group(2).trim());
                if (en != null) out.append(m.group(1)).append(" | ").append(en[0]).append(" | ").append(en[1]).append('\n');
            }
            String reply = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + ClaudeClient.jsonForTests(out.toString())
                    + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":800,\"output_tokens\":150}}";
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        System.setProperty("linkeddesk.apiBase", "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            Settings set = Settings.load();
            set.setApiKey("good");
            set.setClaudeDigest(false);
            set.setClaudeCheckLinks(0);
            set.save();
            ArticleStore store = new ArticleStore(home.resolve("data"));
            store.load();
            List<Article> arts = new ArrayList<>(StorageTest.real(now - 3_600_000L));
            Article es1 = new Article("es1", "https://elpais.example/ucrania", "Ucrania afirma que hundió un buque de guerra ruso frente a Libia con drones navales",
                    "Zelenski dijo que los drones navales alcanzaron un buque de guerra ruso amarrado en un puerto de Libia.", "El País", Category.WORLD, now - 2 * 3_600_000L, now - 2 * 3_600_000L);
            Article es2 = new Article("es2", "https://elpais.example/belgica", "Huelga nacional en Bélgica: cancelados todos los vuelos del lunes",
                    "Los aeropuertos belgas cerrarán el lunes por una huelga nacional contra la austeridad.", "El País", Category.WORLD, now - 2 * 3_600_000L, now - 2 * 3_600_000L);
            arts.add(es1); arts.add(es2);
            store.addAll(arts);
            NewsService svc = new NewsService(store, set);
            Analyzer.Result before = svc.analyze(7);
            StoryCluster alone = before.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.id.equals("es1"))).findFirst().get();
            boolean linkedOffline = alone.articles.size() > 1 || !alone.links.isEmpty();
            c.that(linkedOffline, "without translation, names in Spanish (Ucrania, Libia) already tie it to the English coverage ("
                    + (alone.articles.size() > 1 ? "same story" : alone.links.size() + " links") + ")");
            int n = svc.translateNew();
            c.eq(2, n, "Claude translates the non-English articles after the download (English ones are left alone)");
            Analyzer.Result after = svc.analyze(7);
            StoryCluster ukr = after.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.id.equals("es1"))).findFirst().get();
            StoryCluster bel = after.stories().stream().filter(s -> s.articles.stream().anyMatch(a -> a.id.equals("es2"))).findFirst().get();
            c.that(ukr.articles.size() >= 5 && ukr.articles.stream().anyMatch(a -> a.title.contains("Zelenskyy") || a.title.contains("Zelensky")),
                    "the Spanish article groups with its English counterpart (" + ukr.articles.size() + " articles: " + ukr.headline + ")");
            c.that(bel.articles.size() >= 3 && bel.articles.stream().anyMatch(a -> a.title.toLowerCase().contains("belgi") && !a.id.equals("es2")),
                    "so does the second one (" + bel.headline + ")");
            Article shown = ukr.articles.stream().filter(a -> a.id.equals("es1")).findFirst().get();
            String page = com.linkeddesk.ui.TestAccess.storyPage(ukr, svc);
            c.that(shown.lang.equals("es") && page.contains("Translated from Spanish") && page.contains(shown.originalTitle), "the story page shows it was translated, and the original headline");
            int callsBefore = calls.get();
            c.eq(0, svc.translateNew(), "articles are translated once");
            c.eq(callsBefore, calls.get(), "(no new request)");
            var sr = svc.search(new SearchQuery("warship"), NewsService.SearchFilter.ANY);
            c.that(sr.matches().stream().anyMatch(a -> a.id.equals("es1")), "Search finds it by its English words too (\"warship\")");
            set.reload(); set.setTranslatePerDay(2); set.save();
            store.addAll(List.of(new Article("es3", "https://elpais.example/3", "La inflación en España baja por tercer mes consecutivo según el INE", "", "El País", Category.FINANCE, now - 3_600_000L, now - 3_600_000L)));
            c.eq(0, svc.translateNew(), "no more than the daily limit (Settings)");

            // Full text in search (service)
            store.addAll(full);
            var fsr = svc.search(new SearchQuery("zebrafish"), NewsService.SearchFilter.ANY);
            c.that(fsr.total() == 1 && fsr.inFullText() == 1 && fsr.matches().get(0).id.equals(fa.id), "Search finds words that appear only in a full text (\"zebrafish\")");
            set.reload(); set.setSearchFullText(false); set.save();
            c.eq(0, svc.search(new SearchQuery("zebrafish"), NewsService.SearchFilter.ANY).total(), "… unless switched off in Settings");

            // ------------------------------------------------------------------ UI-F12
            c.suite("Briefing on the phone (UI-F12)");
            Path phone = Files.createTempDirectory("ld-phone");
            set.reload(); set.setPhoneFolder(phone.toString()); set.save();
            svc.watches().save(List.of(new com.linkeddesk.alerts.Watches.Watch("Ukraine", "Ukraine", true)));
            svc.watches().markSeen("Ukraine", now - 10 * 3_600_000L);
            Path out = svc.publishPhone();
            String html = Files.readString(out);
            var b = svc.briefing();
            c.that(out.getFileName().toString().equals(PhonePage.LATEST) && Files.exists(phone.resolve("briefings").resolve(LocalDate.now() + ".html")),
                    "the page is written to the folder (and a dated copy)");
            c.that(html.contains("name=\"viewport\" content=\"width=device-width") && html.contains("prefers-color-scheme:dark"), "made for a phone screen, light or dark like the phone");
            c.that(b.desks().values().stream().flatMap(List::stream).allMatch(x -> html.contains(PhonePageAccess.esc(x.story().headline))), "every top story of the briefing is on it");
            c.that(html.contains("New on your watchlist") && html.contains(">Ukraine <span"), "with what's new on your watchlist");
            c.that(!html.contains("<script") && !html.contains("http://127.0.0.1"), "a plain page: no scripts, nothing loaded from elsewhere");
            for (int i = 1; i <= 20; i++) Files.writeString(phone.resolve("briefings").resolve(LocalDate.now().minusDays(i) + ".html"), "old");
            svc.publishPhone();
            long kept;
            try (var s = Files.list(phone.resolve("briefings"))) { kept = s.count(); }
            String latest = Files.readString(phone.resolve(PhonePage.LATEST));
            c.eq((long) PhonePage.KEEP_DAYS, kept, "the last two weeks are kept");
            c.that(latest.contains("href=\"briefings/" + LocalDate.now().minusDays(1) + ".html\"") && !latest.contains(LocalDate.now().minusDays(15).toString()), "with links to earlier days");
            c.that(Files.readString(phone.resolve("briefings").resolve(LocalDate.now() + ".html")).contains("href=\"../" + PhonePage.LATEST + "\""), "the dated copies link back to the latest");
        } finally {
            server.stop(0);
            System.clearProperty("linkeddesk.apiBase");
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }

    /** Escaping as the phone page does it. */
    static final class PhonePageAccess { static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); } }
}
