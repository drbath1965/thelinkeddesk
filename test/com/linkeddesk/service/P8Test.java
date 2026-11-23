package com.linkeddesk.service;

import com.linkeddesk.Check;
import com.linkeddesk.ai.Framing;
import com.linkeddesk.analysis.Categorizer;
import com.linkeddesk.analysis.Coverage;
import com.linkeddesk.analysis.SinceLastVisit;
import com.linkeddesk.analysis.TopicSuggester;
import com.linkeddesk.app.AppPaths;
import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.schedule.WindowsTask;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** P8 (v1.8): independent coverage, opinion, Defense desk, suggested topics, since last visit, framing, Check this PC, updates. */
public final class P8Test {
    static final long H = 3_600_000L, DAY = 24 * H;
    static int seq;

    static Article art(String source, String url, String title, String summary, long published, long fetched) {
        return new Article("p8-" + (seq++), url, title, summary, source, Category.WORLD, published, fetched);
    }

    static StoryCluster story(String headline, List<Article> as) {
        StoryCluster c = new StoryCluster("s" + (seq++));
        c.headline = headline;
        for (Article a : as) c.add(a);
        c.copies = Coverage.copies(c.articles);
        c.recount();
        return c;
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();

        // ------------------------------------------------------------------ ANL-F12 / ANL-F13
        c.suite("Independent coverage and opinion (ANL-F12, ANL-F13)");
        String ap = "WASHINGTON (AP) — The Senate voted 52-48 on Tuesday to approve a stopgap spending bill that keeps the government open through January, sending it to the president.";
        String apEdited = "WASHINGTON (AP) — The Senate voted 52-48 Tuesday to approve a stopgap spending bill that keeps the government open through January, sending the measure to the president.";
        Article a1 = art("Chicago Tribune", "https://chicagotribune.com/a", "Senate passes stopgap bill", ap, now, now);
        Article a2 = art("Seattle Times", "https://seattletimes.com/b", "Senate approves stopgap spending", apEdited, now, now);
        Article a3 = art("Houston Chronicle", "https://houstonchronicle.com/c", "Shutdown averted", "Senators approved the measure late Tuesday, the Senate said. (AP)", now, now);
        Article a4 = art("BBC News", "https://bbc.co.uk/d", "US Senate avoids shutdown", "Our correspondent says the vote ends weeks of brinkmanship in Washington over the budget.", now, now);
        Article a5 = art("The Guardian", "https://theguardian.com/commentisfree/e", "The shutdown fight was pointless", "Congress should stop governing by crisis.", now, now);
        Article a6 = art("Politico", "https://politico.com/f", "Analysis: What the stopgap means for the budget", "The deal pushes the hard choices into the new year.", now, now);
        c.eq("AP", Coverage.agency(a1), "a (AP) dateline is read as an AP report");
        c.eq("AP", Coverage.agency(a3), "a trailing (AP) tag too");
        c.eq("AP", Coverage.agency(art("AP News", "https://apnews.com/x", "t", "s", now, now)), "AP's own outlet is AP");
        c.eq("", Coverage.agency(a4), "the BBC's own reporting has no agency");
        c.that(Coverage.opinion(a5), "a /commentisfree/ link is opinion");
        c.that(Coverage.opinion(a6), "an \"Analysis:\" headline is analysis");
        c.that(!Coverage.opinion(a4), "ordinary reporting isn't opinion");
        c.that(Coverage.opinion(art("NYT Opinion", "https://nytimes.com/x", "t", "s", now, now)), "a feed named Opinion is opinion");
        StoryCluster st = story("Senate passes stopgap bill", List.of(a1, a2, a3, a4, a5, a6));
        c.eq(1, st.copies.size(), "the three AP copies form one group");
        c.eq(3, st.copies.get(0).outlets(), "…of three outlets");
        c.eq(6, st.sourceCount(), "six outlets in all");
        c.eq(2, st.independentCount(), "two independent reports: AP (once) and the BBC; the opinion and analysis pieces don't count");
        // Same text without a wire tag
        String text = "Heavy rain caused flooding across the region on Sunday, closing schools and roads, and officials urged residents to stay at home until the water recedes.";
        StoryCluster same = story("Floods", List.of(art("Outlet A", "https://a.com/1", "Floods", text, now, now), art("Outlet B", "https://b.com/1", "Flooding", text + " More to follow.", now, now),
                art("Outlet C", "https://c.com/1", "Flood warning", "Rivers burst their banks in three towns; our reporter visited the worst-hit street and spoke to families.", now, now)));
        c.eq(1, same.copies.size(), "the same text from two outlets is one copy group");
        c.eq("the same text", same.copies.get(0).label(), "…labelled as the same text");
        c.eq(2, same.independentCount(), "so three outlets are two independent reports");
        // No false merges: different reports on one subject
        StoryCluster diff = story("Election", List.of(
                art("Outlet A", "https://a.com/2", "Count begins", "Officials began counting ballots in the capital after polls closed at eight in the evening on Sunday night.", now, now),
                art("Outlet B", "https://b.com/2", "Turnout high", "Turnout reached a record high, the election commission said, with long queues reported at many rural polling stations.", now, now),
                art("Outlet C", "https://c.com/2", "Opposition claims", "The opposition leader claimed irregularities in two provinces and called for observers to review the results.", now, now)));
        c.eq(0, diff.copies.size(), "different reports about one event aren't merged");
        c.eq(3, diff.independentCount(), "…and all three count");
        StoryCluster allOpinion = story("Views", List.of(a5, a6));
        c.eq(2, allOpinion.independentCount(), "a story that is only opinion still shows its outlet count");

        // ------------------------------------------------------------------ Defense desk
        c.suite("Defense & Space desk");
        c.that(Category.parse("Defense") == Category.DEFENSE && Category.parse("space") == Category.DEFENSE, "the desk is read from feeds.txt as Defense (or Space)");
        c.eq(Category.DEFENSE, Categorizer.categorize(art("Reuters", "https://r.com/x", "Army awards hypersonic missile contract to Lockheed Martin",
                "The Pentagon said the Army contract covers the next phase of the program.", now, now)), "a Pentagon contract story goes to Defense");
        c.eq(Category.DEFENSE, Categorizer.categorize(art("Reuters", "https://r.com/y", "NASA delays Artemis launch", "The SpaceX rocket and the crew capsule need more checks.", now, now)), "a NASA launch goes to Defense & Space");
        c.eq(Category.DEFENSE, Categorizer.categorize(art("WHNT", "https://whnt.com/z", "Redstone Arsenal adds 400 jobs", "Huntsville's Redstone Arsenal will add jobs.", now, now)), "Redstone Arsenal and Huntsville go to Defense");
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-p8");
        System.setProperty("linkeddesk.home", home.toString());
        try {
            Files.writeString(home.resolve(".feeds-v1.1"), "test");
            Files.writeString(AppPaths.feedsFile(), "Tech | SpaceNews | https://spacenews.com/feed/\nTech | The Verge | https://www.theverge.com/rss/index.xml\n# Tech | SpaceNews | https://example.com/old\n");
            List<FeedSource> feeds = FeedCatalog.load();
            String text18 = Files.readString(AppPaths.feedsFile());
            c.that(feeds.stream().anyMatch(f -> f.name().equals("SpaceNews") && f.category() == Category.DEFENSE),
                    "upgrading to 1.8 moves SpaceNews to the Defense desk");
            c.that(feeds.stream().anyMatch(f -> f.name().equals("The Verge") && f.category() == Category.TECH), "other feeds stay where they were");
            c.that(feeds.stream().anyMatch(f -> f.name().contains("Army Times")) && feeds.stream().anyMatch(f -> f.name().contains("WHNT")), "the new Defense feeds (Army Times, NASA, WHNT…) are added");
            c.that(Files.exists(home.resolve(".feeds-v1.8")), "the upgrade is recorded so it runs once");
            Files.writeString(AppPaths.feedsFile(), "Tech | The Verge | https://www.theverge.com/rss/index.xml\n");
            List<FeedSource> again = FeedCatalog.load();
            c.that(again.size() == 1, "feeds you remove later aren't added back");
            c.that(text18.contains("# Tech | SpaceNews | https://example.com/old"), "commented-out lines are left alone");
        } finally {
            if (oldHome == null) System.clearProperty("linkeddesk.home"); else System.setProperty("linkeddesk.home", oldHome);
        }

        // ------------------------------------------------------------------ Topic suggestions
        c.suite("Suggested topics");
        List<StoryCluster> two = new ArrayList<>();
        Set<StoryCluster> read = new HashSet<>(), starred = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            StoryCluster s = story("Redstone Arsenal contract " + i, List.of(art("WHNT", "https://whnt.com/r" + i, "Redstone Arsenal contract " + i, "Work at Redstone Arsenal grows.", now - i * H, now)));
            s.keyTerms = List.of("Redstone Arsenal", "contract");
            s.category = Category.DEFENSE;
            two.add(s);
            read.add(s);
            if (i < 1) starred.add(s);
        }
        for (int i = 0; i < 6; i++) {
            StoryCluster s = story("Hormuz shipping " + i, List.of(art("Reuters", "https://r.com/h" + i, "Tankers avoid Hormuz " + i, "Shipping through Hormuz slows.", now - i * H, now)));
            s.keyTerms = List.of("Hormuz", "shipping");
            two.add(s);
        }
        var sug = TopicSuggester.suggest(two, now, read::contains, starred::contains, List.of(), List.of(), Map.of("Golden Dome", "\"golden dome\""), Set.of(), 5);
        var reading = sug.stream().filter(s -> s.kind().equals("reading")).toList();
        c.that(reading.size() == 1 && reading.get(0).name().equals("Redstone Arsenal") && reading.get(0).desk() == Category.DEFENSE,
                "what you read and star becomes a suggestion, on its desk: " + reading);
        c.that(reading.get(0).query().equals("\"Redstone Arsenal\""), "a two-word name is searched as a phrase");
        c.that(sug.stream().anyMatch(s -> s.kind().equals("rising") && s.name().equals("Hormuz")), "a subject with 6 stories this week and none before is rising");
        c.that(sug.stream().noneMatch(s -> s.name().equalsIgnoreCase("contract") || s.name().equalsIgnoreCase("shipping")), "ordinary words aren't suggested");
        c.that(sug.stream().anyMatch(s -> s.kind().equals("watch") && s.name().equals("Golden Dome")), "a watch can be turned into a topic");
        c.that(sug.stream().noneMatch(s -> s.kind().equals("starter")), "starters aren't offered when you already have topics");
        var again = TopicSuggester.suggest(two, now, read::contains, starred::contains, List.of("redstone arsenal"), List.of("golden dome"), Map.of("Golden Dome", "\"golden dome\""), Set.of("hormuz"), 0);
        c.that(again.stream().noneMatch(s -> s.name().equals("Redstone Arsenal")), "a subject you already follow isn't suggested again");
        c.that(again.stream().noneMatch(s -> s.name().equals("Hormuz")), "one you said you're not interested in isn't either");
        c.that(again.stream().noneMatch(s -> s.kind().equals("watch")), "a watch already covered by a topic isn't offered");
        c.that(again.stream().anyMatch(s -> s.kind().equals("starter")), "with few topics, starters are offered");

        // ------------------------------------------------------------------ Since last visit
        c.suite("Since last visit");
        long since = now - 10 * H;
        StoryCluster fresh = story("New story", List.of(art("A", "https://a.com/n", "New", "x", now - H, now - H), art("B", "https://b.com/n", "New", "y", now - H, now - H)));
        StoryCluster more = story("Older story", List.of(art("A", "https://a.com/o", "Old", "x", now - 2 * DAY, now - 2 * DAY), art("C", "https://c.com/o", "Old", "z", now - H, now - 2 * H)));
        StoryCluster stale = story("Nothing new", List.of(art("A", "https://a.com/s", "Stale", "x", now - 2 * DAY, now - 2 * DAY)));
        StoryCluster watched = story("Hypersonic test", List.of(art("D", "https://d.com/w", "Hypersonic test", "x", now - H, now - H)));
        var r = SinceLastVisit.compute(List.of(stale, more, fresh, watched), since, s -> s == watched ? List.of("Hypersonic") : List.of());
        c.eq(3, r.items().size(), "stories with nothing new since the last visit are left out");
        c.eq(SinceLastVisit.Kind.WATCH, r.items().get(0).kind(), "watchlist stories come first");
        c.that(r.items().stream().anyMatch(i -> i.story() == fresh && i.kind() == SinceLastVisit.Kind.NEW), "a story that started since is new");
        c.that(r.items().stream().anyMatch(i -> i.story() == more && i.kind() == SinceLastVisit.Kind.MORE && i.tag().equals("1 new article")), "an older story with new coverage says how much");
        c.eq("1 watchlist story · 1 new story · 1 story with new coverage", r.summary(), "the summary line");
        c.eq("Nothing new since you last looked.", SinceLastVisit.compute(List.of(stale), since, s -> List.of()).summary(), "and when there's nothing");

        // ------------------------------------------------------------------ AI-F07 framing
        c.suite("How outlets framed it (AI-F07)");
        Map<String, Article> refs = Framing.outlets(st, x -> x == a4 ? "Full text from the BBC feed about the vote." : "");
        c.eq(6, refs.size(), "one article per outlet is sent");
        String prompt = Framing.prompt(st, refs, x -> x == a4 ? "Full text from the BBC feed about the vote." : "");
        c.that(prompt.contains("[O1]") && prompt.contains("do not judge which") && prompt.contains("Text: Full text from the BBC"), "the prompt numbers outlets, asks for no verdict, and includes feed full text");
        String reply = """
                **COMMON:**
                - The Senate passed the stopgap 52-48 [O1][O2]
                EMPHASIS:
                - The BBC stresses weeks of brinkmanship [O3] [O9]
                ONLY SOME:
                - Only one gives the vote count [O1]
                WORDING:
                - "Shutdown averted" versus "approves stopgap" [O2][O4]
                """;
        var fr = Framing.parse(reply, st, refs);
        c.eq(1, fr.sections().get("COMMON").size(), "sections are read, even in bold");
        c.that(fr.sections().get("EMPHASIS").get(0).endsWith("brinkmanship [O3]"), "a citation to an outlet that wasn't sent is removed: " + fr.sections().get("EMPHASIS"));
        c.that(fr.problems().size() == 1 && fr.problems().get(0).contains("O9"), "…and noted");
        c.that(fr.cited().containsAll(List.of("O1", "O2", "O3", "O4")) && !fr.cited().contains("O5"), "which outlets were cited is kept");
        String page = com.linkeddesk.ui.TestAccess.framingHtml(fr);
        c.that(page.contains("href='" + refs.get("O1").url + "'") && page.contains("Outlets Claude was given") && page.contains("not cited"), "the page links each citation to that outlet's article");

        // ------------------------------------------------------------------ OPS-F06
        c.suite("Check this PC (OPS-F06)");
        SelfCheck.Facts f = new SelfCheck.Facts();
        f.now = now; f.javaFeature = 21; f.windows = true;
        f.task = WindowsTask.fields("Folder: \\\nHostName: PC\nTaskName: \\The Linked Desk daily news download\nNext Run Time: 11/24/2026 7:00:00 AM\nStatus: Ready\nLast Run Time: 11/23/2026 7:00:01 AM\nLast Result: 0\n");
        f.taskRunsThisCopy = true; f.hasKey = true; f.keyReadable = true; f.keyStart = "sk-ant-";
        f.traySupported = true; f.dataDir = Path.of("C:/Users/x/.linkeddesk/data"); f.dataWritable = true; f.freeBytes = 50L << 30;
        f.lastFetch = now - 3 * H; f.feeds = 60; f.months = 3; f.indexFiles = 3;
        f.backupFolder = "D:/Backup"; f.backupFolderExists = true; f.backupLast = now - 2 * DAY; f.backupWeekly = true;
        f.screenW = 1920; f.screenH = 1040; f.scale = 1.5; f.windowW = 1400; f.windowH = 900;
        f.latestVersion = AppPaths.VERSION;
        var checks = SelfCheck.evaluate(f);
        c.eq(SelfCheck.Status.OK, SelfCheck.overall(checks), "a healthy PC passes: " + checks.stream().filter(x -> x.status() != SelfCheck.Status.OK && x.status() != SelfCheck.Status.INFO).toList());
        c.eq("Ready", f.task.get("Status"), "schtasks output is read");
        f.javaFeature = 17; f.keyReadable = false; f.failing = 20; f.failingNames = List.of("Feed A", "Feed B");
        f.task = new HashMap<>(f.task); f.task.put("Status", "Disabled");
        f.taskRunsThisCopy = false; f.backupFolderExists = false; f.windowW = 2600; f.lastFetch = now - 3 * DAY;
        f.latestVersion = "99.0.0";
        checks = SelfCheck.evaluate(f);
        Map<String, SelfCheck.Check> by = new HashMap<>();
        for (var x : checks) by.putIfAbsent(x.name(), x);
        c.eq(SelfCheck.Status.FAIL, by.get("Java").status(), "old Java fails, with how to fix it");
        c.eq(SelfCheck.Status.FAIL, by.get("Daily download task").status(), "a disabled task fails");
        c.eq(SelfCheck.Status.WARN, by.get("Task runs this copy").status(), "a task running another copy is flagged");
        c.that(by.get("Anthropic API key").status() == SelfCheck.Status.FAIL && by.get("Anthropic API key").detail().contains("another account"), "a key that can't be decrypted is explained");
        c.that(by.get("Feeds").status() == SelfCheck.Status.FAIL && by.get("Feeds").detail().contains("Feed A") && by.get("Feeds").fix().contains("firewall"), "many failing feeds point to the connection");
        c.eq(SelfCheck.Status.FAIL, by.get("Backup").status(), "a missing backup folder fails");
        c.eq(SelfCheck.Status.WARN, by.get("Screen and scaling").status(), "a window bigger than the screen is flagged");
        c.eq(SelfCheck.Status.WARN, by.get("Last download").status(), "no download for three days is flagged");
        c.eq(SelfCheck.Status.WARN, by.get("Updates").status(), "a newer version is mentioned");
        String rep = SelfCheck.report(checks, f);
        c.that(rep.contains("[FAIL] Java") && rep.contains("Fix: "), "the saved report lists each check and its fix");
        f.task.put("Status", "Ready"); f.task.put("Last Result", "1");
        c.eq(SelfCheck.Status.WARN, SelfCheck.evaluate(f).stream().filter(x -> x.name().equals("Daily download task")).findFirst().get().status(), "a run that ended with an error is flagged");
        f.task.put("Last Result", "267011");
        c.eq(SelfCheck.Status.OK, SelfCheck.evaluate(f).stream().filter(x -> x.name().equals("Daily download task")).findFirst().get().status(), "\"has not run yet\" (267011) is fine");

        // ------------------------------------------------------------------ OPS-F07
        c.suite("Updates from GitHub releases (OPS-F07)");
        c.that(UpdateCheck.compare("1.10.0", "1.9.2") > 0 && UpdateCheck.compare("v1.8.0", "1.8") == 0 && UpdateCheck.compare("1.8.0-beta", "1.8.0") == 0
                && UpdateCheck.compare("1.7.1", "1.8.0") < 0, "versions compare by number (1.10 > 1.9, v and -suffix ignored)");
        c.that(UpdateCheck.due(true, 0, now) && !UpdateCheck.due(true, now - H, now) && !UpdateCheck.due(false, 0, now), "the automatic check runs at most once a day, and can be switched off");
        Path rel = Files.createTempDirectory("ld-rel");
        Path goodJar = rel.resolve("good.jar"), fakeJar = rel.resolve("fake.jar");
        makeJar(goodJar, "com.linkeddesk.app.App", "9.9.0", true);
        makeJar(fakeJar, "org.example.Evil", null, false);
        byte[] good = Files.readAllBytes(goodJar), fake = Files.readAllBytes(fakeJar);
        String[] which = {"good"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port;
        server.createContext("/repos/x/releases/latest", ex -> {
            String base = "http://127.0.0.1:" + ex.getLocalAddress().getPort();
            byte[] body = which[0].equals("none") ? "{\"message\":\"Not Found\"}".getBytes() : ("""
                    {"tag_name":"v9.9.0","name":"The Linked Desk 9.9.0","html_url":"https://github.com/x/releases/tag/v9.9.0",
                     "body":"## What's new\\n- Faster \\"search\\"\\n- \\u00e9t\\u00e9","assets":[
                       {"name":"TheLinkedDesk-9.9.0.zip","browser_download_url":"%s/zip","size":5},
                       {"name":"LinkedDesk-9.9.0.jar","browser_download_url":"%s/jar","size":%d}]}""")
                    .formatted(base, base, which[0].equals("fake") ? fake.length : which[0].equals("short") ? good.length + 10 : good.length).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(which[0].equals("none") ? 404 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/jar", ex -> {
            byte[] b = which[0].equals("fake") ? fake : good;
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        try {
            UpdateCheck uc = new UpdateCheck("http://127.0.0.1:" + port + "/repos/x/releases/latest");
            UpdateCheck.Release latest = uc.latest();
            c.eq("9.9.0", latest.version(), "the newest release's version is read from its tag");
            c.that(latest.notes().contains("Faster \"search\"") && latest.notes().contains("été"), "release notes are read (escapes and accents)");
            c.that(latest.jarName().equals("LinkedDesk-9.9.0.jar") && latest.jarSize() == good.length, "the jar is picked from the assets, not the zip");
            Path dir = Files.createTempDirectory("ld-upd");
            long[] seen = {0};
            Path got = uc.download(latest, dir, n -> seen[0] = n);
            c.that(got.getFileName().toString().equals("LinkedDesk-9.9.0.jar") && Files.size(got) == good.length && seen[0] == good.length, "it downloads next to this copy as LinkedDesk-9.9.0.jar");
            c.that(!Files.exists(dir.resolve("LinkedDesk-9.9.0.jar.part")), "no partial file is left");
            c.eq(got, uc.download(latest, dir, null), "a good copy already there is reused");
            which[0] = "fake";
            Path dir2 = Files.createTempDirectory("ld-upd");
            String err = "";
            try { uc.download(uc.latest(), dir2, null); } catch (Exception e) { err = e.getMessage(); }
            c.that(err.contains("isn't The Linked Desk") && !Files.exists(dir2.resolve("LinkedDesk-9.9.0.jar")) && !Files.exists(dir2.resolve("LinkedDesk-9.9.0.jar.part")),
                    "a jar that isn't the app is refused and deleted: " + err);
            which[0] = "short";
            err = "";
            try { uc.download(uc.latest(), Files.createTempDirectory("ld-upd"), null); } catch (Exception e) { err = e.getMessage(); }
            c.that(err.contains("incomplete"), "a download of the wrong size is refused: " + err);
            which[0] = "none";
            c.that(uc.latest() == null, "a repository with no releases yet is not an error");
            String wrongVer = "";
            try { UpdateCheck.validate(goodJar, new UpdateCheck.Release("9.8.0", "", "", "", "u", "n", 0)); } catch (Exception e) { wrongVer = e.getMessage(); }
            c.that(wrongVer.contains("9.9.0"), "a jar of a different version than the release is refused");
            c.that(UpdateCheck.updatableFolder(Path.of("/x/classes")) == null, "running from classes (not a jar) can't update itself");
            c.eq(dir, UpdateCheck.updatableFolder(dir.resolve("LinkedDesk-1.8.0.jar")), "a jar in a writable folder updates next to itself");
        } finally {
            server.stop(0);
        }
    }

    static void makeJar(Path p, String main, String version, boolean withApp) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        if (version != null) m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p), m)) {
            if (withApp) {
                out.putNextEntry(new JarEntry("com/linkeddesk/app/App.class")); out.write(new byte[]{(byte) 0xCA, (byte) 0xFE}); out.closeEntry();
                out.putNextEntry(new JarEntry("com/linkeddesk/app/AppPaths.class")); out.write(new byte[]{1, 2, 3}); out.closeEntry();
            }
            out.putNextEntry(new JarEntry("filler.txt")); out.write("x".repeat(4000).getBytes()); out.closeEntry();
        }
    }
}
