package com.linkeddesk.service;

import com.linkeddesk.Check;
import com.linkeddesk.ai.Explainer;
import com.linkeddesk.alerts.Watches;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.ComingUp;
import com.linkeddesk.analysis.Coverage;
import com.linkeddesk.analysis.EntityPage;
import com.linkeddesk.analysis.QuickRead;
import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.SafeFiles;
import com.linkeddesk.app.Settings;
import com.linkeddesk.app.Speech;
import com.linkeddesk.export.CaseFileDoc;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.CaseFiles;
import com.linkeddesk.store.ConnectionStore;
import com.linkeddesk.store.ReadState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** P12 (v1.12): review fixes and offline features. */
public final class P12Test {
    static final long H = 3_600_000L, DAY = 24 * H;
    static int seq;

    static Article art(String source, String title, String summary, long when) {
        return new Article("p12-" + (seq++), "https://ex.com/" + seq, title, summary, source, Category.WORLD, when, when);
    }

    static StoryCluster story(Article... as) {
        StoryCluster c = new StoryCluster("s" + (seq++));
        c.headline = as[0].title;
        c.category = Category.WORLD;
        for (Article a : as) c.add(a);
        c.copies = Coverage.copies(c.articles);
        c.recount();
        return c;
    }

    /** Makes a file unreadable the way a lock does: replace it with a folder of the same name. */
    static void lock(Path f) throws Exception { Files.delete(f); Files.createDirectory(f); }
    static void unlock(Path f, String text) throws Exception { Files.delete(f); Files.writeString(f, text); }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-p12");
        System.setProperty("linkeddesk.home", home.toString());
        Files.writeString(home.resolve(".feeds-v1.1"), "t"); Files.writeString(home.resolve(".feeds-v1.8"), "t");
        try {
            // ------------------------------------------------------------------ OPS-F08
            c.suite("Never overwrite saved data after a failed read (OPS-F08)");
            Path f = home.resolve("x.txt");
            c.eq("", SafeFiles.readString(f), "a missing file reads as empty");
            SafeFiles.write(f, "one");
            SafeFiles.write(f, "two");
            c.that(Files.readString(f).equals("two") && Files.readString(SafeFiles.backup(f)).equals("one"), "every write keeps the previous version as .bak");
            Files.write(f, new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF});
            c.eq("one", SafeFiles.readString(f), "a damaged file is read from its backup");
            lock(f);
            boolean threw = false;
            try { SafeFiles.readString(f); } catch (SafeFiles.Unreadable u) { threw = true; }
            c.that(threw, "a file that can't be read (and isn't just damaged) is reported, not read as empty");
            c.that(SafeFiles.problems().stream().anyMatch(p -> p.contains("x.txt")), "and the problem is listed for Check this PC");

            Path data = home.resolve("data");
            Files.createDirectories(data);
            ReadState rs = new ReadState(data).load();
            StoryCluster s1 = story(art("A", "First starred story", "x", now)), s2 = story(art("B", "Second starred story", "y", now)), s3 = story(art("C", "Third", "z", now));
            rs.star(s1); rs.star(s2);
            Path stars = data.resolve("starred.tsv");
            String good = Files.readString(stars);
            lock(stars);
            ReadState rs2 = new ReadState(data).load();
            String err = "";
            try { rs2.star(s3); } catch (java.io.IOException e) { err = e.getMessage(); }
            c.that(err.contains("couldn't be read") && err.contains("nothing was lost"), "starring while the stars file can't be read is refused: " + err);
            unlock(stars, good);
            rs2.load();
            rs2.star(s3);
            ReadState rs3 = new ReadState(data).load();
            c.that(rs3.isStarred(s1) && rs3.isStarred(s2) && rs3.isStarred(s3), "once it can be read again, every earlier star is kept and the new one added");
            Files.write(stars, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC3});
            ReadState rs4 = new ReadState(data).load();
            c.that(rs4.isStarred(s1) && rs4.isStarred(s2), "a damaged stars file is restored from its .bak");

            ConnectionStore cs = new ConnectionStore(home.resolve("connections.tsv"));
            var cand = new com.linkeddesk.ai.Connections.Candidate("C1", s2, "test");
            cs.record(new com.linkeddesk.ai.Connections.Result(s1, List.of(cand), List.of(new com.linkeddesk.ai.Connections.Found(cand, com.linkeddesk.ai.Connections.Kind.CAUSE,
                    com.linkeddesk.ai.Connections.Confidence.HIGH, "r")), List.of()), now);
            String connText = Files.readString(home.resolve("connections.tsv"));
            lock(home.resolve("connections.tsv"));
            ConnectionStore cs2 = new ConnectionStore(home.resolve("connections.tsv"));
            err = "";
            try { cs2.record(new com.linkeddesk.ai.Connections.Result(s3, List.of(), List.of(), List.of()), now + 1); } catch (java.io.IOException e) { err = e.getMessage(); }
            c.that(!err.isEmpty(), "Claude connections aren't saved over an unreadable file");
            unlock(home.resolve("connections.tsv"), connText);
            c.eq(1, new ConnectionStore(home.resolve("connections.tsv")).suggestions(s1).size(), "…and are all still there");

            Path fh = home.resolve("feed-health.tsv");
            FeedHealth h = new FeedHealth(fh).load();
            h.get("https://a.com/rss").name = "A";
            h.save();
            String fhText = Files.readString(fh);
            lock(fh);
            FeedHealth h2 = new FeedHealth(fh).load();
            err = "";
            try { h2.save(); } catch (java.io.IOException e) { err = e.getMessage(); }
            c.that(!err.isEmpty(), "feed health isn't saved over an unreadable file");
            unlock(fh, fhText);

            TopicCatalog.save(List.of(new Topic(Category.DEFENSE, "Golden Dome", "\"golden dome\"", "en-US", false)));
            String topicsText = Files.readString(TopicCatalog.file());
            lock(TopicCatalog.file());
            List<Topic> none = TopicCatalog.load();
            err = "";
            try { List<Topic> l = new ArrayList<>(none); l.add(new Topic(Category.WORLD, "New", "new", "en-US", false)); TopicCatalog.save(l); } catch (java.io.IOException e) { err = e.getMessage(); }
            c.that(none.isEmpty() && !err.isEmpty(), "topics aren't saved from an empty list read by mistake");
            unlock(TopicCatalog.file(), topicsText);
            c.eq(1, TopicCatalog.load().size(), "…and are still there");

            Watches w = new Watches(home);
            w.save(List.of(new Watches.Watch("Hormuz", "hormuz", true)));
            String watchText = Files.readString(home.resolve("watchlist.txt"));
            lock(home.resolve("watchlist.txt"));
            Watches w2 = new Watches(home).load(null);
            err = "";
            try { w2.save(List.of(new Watches.Watch("Only", "only", true))); } catch (java.io.IOException e) { err = e.getMessage(); }
            c.that(!err.isEmpty(), "the watchlist isn't saved over an unreadable file");
            unlock(home.resolve("watchlist.txt"), watchText);

            Settings st = Settings.load();
            st.setClaudeBudget(7.5); st.save();
            String setText = Files.readString(AppPaths.settingsFile());
            lock(AppPaths.settingsFile());
            Settings st2 = Settings.load();
            st2.setClaudeBudget(1.0);
            st2.save();
            unlock(AppPaths.settingsFile(), setText);
            c.that(st2.unreadable() && Settings.load().claudeBudget() == 7.5, "settings aren't saved from defaults read by mistake");

            // ------------------------------------------------------------------ OPS-F09
            c.suite("Log errors that were ignored (OPS-F09)");
            com.linkeddesk.app.Logs.error("Placing stories on the World map", new StringIndexOutOfBoundsException("Index 0 out of bounds for length 0"));
            var errs = com.linkeddesk.app.Logs.errorsSince(java.time.LocalDateTime.now().minusMinutes(5));
            c.that(errs.stream().anyMatch(e -> e.contains("World map")), "an error drawing a view is written to errors.log");
            String log = com.linkeddesk.app.Logs.errorLogText();
            c.that(log.contains("StringIndexOutOfBoundsException") && log.contains("at "), "with its stack trace");
            SelfCheck.Facts facts = new SelfCheck.Facts();
            facts.errorsThisWeek = errs;
            facts.fileProblems = SafeFiles.problems();
            var checks = SelfCheck.evaluate(facts);
            c.that(checks.stream().anyMatch(k -> k.name().equals("Errors this week") && k.detail().contains("World map")), "Check this PC lists this week's errors, newest first: "
                    + checks.stream().filter(k -> k.name().equals("Errors this week")).map(SelfCheck.Check::detail).findFirst().orElse("none"));
            c.that(checks.stream().anyMatch(k -> k.name().equals("Saved files") && k.status() == SelfCheck.Status.WARN), "and file read problems");
            facts.settingsUnreadable = true;
            c.that(SelfCheck.evaluate(facts).stream().anyMatch(k -> k.name().equals("Saved files") && k.status() == SelfCheck.Status.FAIL), "an unreadable settings file is a failure with a fix");

            // ------------------------------------------------------------------ ANL-F15
            c.suite("Quick read without Claude (ANL-F15)");
            StoryCluster q = story(
                    art("Reuters", "Senate passes stopgap bill", "WASHINGTON (Reuters) - The Senate voted 52-48 on Tuesday to approve a stopgap bill that keeps the government open through January. The House approved it last week.", now),
                    art("AP", "Senate approves funding", "The Senate approved a stopgap spending bill 52-48, keeping the government funded through January. The president is expected to sign it on Wednesday.", now),
                    art("BBC", "US Senate avoids shutdown", "Senators voted 52-48 to keep the government open through January. Our correspondent says the vote ends weeks of brinkmanship in Washington.", now),
                    art("NYT Opinion", "Opinion: The shutdown fight was pointless", "Congress should stop governing by crisis and pass a real budget for the year.", now),
                    art("CNN", "Shutdown averted", "The government will stay open through January after the Senate vote. The bill now goes to the president, who is expected to sign it on Wednesday …", now));
            var lines = QuickRead.of(q);
            c.that(lines.size() == 3, "a story with 5 outlets gets three sentences: " + lines.stream().map(QuickRead.Line::text).toList());
            c.that(lines.get(0).text().contains("52-48") && lines.get(0).alsoReported() >= 2, "the lead is the fact most outlets agree on, with how many others report it");
            c.that(lines.stream().noneMatch(l -> l.from().source.contains("Opinion")), "opinion isn't used");
            c.that(lines.stream().noneMatch(l -> l.text().startsWith("WASHINGTON")), "datelines are removed");
            c.that(lines.stream().noneMatch(l -> l.text().endsWith("…")), "sentences cut off by a feed are left out");
            Set<String> texts = new HashSet<>();
            for (var l : lines) texts.add(l.text());
            c.eq(lines.size(), texts.size(), "no sentence twice");
            c.that(lines.stream().allMatch(l -> q.articles.contains(l.from())), "each sentence is credited to the outlet that wrote it (nothing reworded)");
            c.that(QuickRead.of(story(art("Solo", "One outlet", "Only one outlet wrote this sentence about something.", now))).isEmpty(), "single-outlet stories don't get one");
            String page = com.linkeddesk.ui.TestAccess.storyHtml(q, List.of(), 0);
            c.that(page.contains("Quick read") && page.contains(lines.get(0).text().substring(0, 20)), "it's at the top of the story page");

            // ------------------------------------------------------------------ ANL-F16
            c.suite("Coming up (ANL-F16)");
            LocalDate pub = LocalDate.of(2026, 11, 23);   // a Monday
            long t = pub.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Object[][] cases = {
                    {"The Senate will vote on the bill on Nov 29.", LocalDate.of(2026, 11, 29)},
                    {"The launch is scheduled for December 3, 2026.", LocalDate.of(2026, 12, 3)},
                    {"Talks are set to resume next Tuesday in Geneva.", LocalDate.of(2026, 12, 1)},
                    {"Leaders will meet on Wednesday to agree the plan.", LocalDate.of(2026, 11, 25)},
                    {"Unions plan a national strike tomorrow.", LocalDate.of(2026, 11, 24)},
                    {"The court is expected to rule on 2 December.", LocalDate.of(2026, 12, 2)},
                    {"The new tariffs take effect Jan 1.", LocalDate.of(2027, 1, 1)},
                    {"Elections are due in March.", LocalDate.of(2027, 3, 1)},
                    {"The prime minister set a year-end deadline for the deal.", LocalDate.of(2026, 12, 1)},
                    {"The deadline for bids is Dec. 15.", LocalDate.of(2026, 12, 15)},
                    {"The minister said on Tuesday that talks had failed.", null},
                    {"Officials met on Nov 20 to discuss the budget.", null},
                    {"Last Friday, the court ruled against the plan.", null},
                    {"The company reported results on Monday.", null}};
            int right = 0, wrongDates = 0;
            for (Object[] k : cases) {
                var ev = ComingUp.inSentence((String) k[0], pub, art("X", "t", "s", t));
                LocalDate want = (LocalDate) k[1];
                if (want == null) { if (ev.isEmpty()) right++; else wrongDates++; }
                else if (!ev.isEmpty() && ev.get(0).date().equals(want)) right++;
                else if (!ev.isEmpty()) wrongDates++;
            }
            c.that(right >= 13, "of 14 test sentences, " + right + " read right (future dates found, past ones ignored)");
            c.eq(0, wrongDates, "no wrong dates");
            var found = ComingUp.find(List.of(art("Reuters", "Senate to vote Nov 29", "The Senate will vote on the bill on Nov 29.", t),
                    art("AP", "Vote set", "Senators will vote on the bill on Nov 29, leaders said.", t),
                    art("BBC", "Old news", "The court ruled on Nov 20.", t)), pub, 30, ZoneId.systemDefault());
            c.that(found.size() == 1 && found.get(0).date().equals(LocalDate.of(2026, 11, 29)), "the same event from several outlets is listed once");

            // ------------------------------------------------------------------ UI-F17
            c.suite("Shorter coverage lists (UI-F17)");
            List<Article> many = new ArrayList<>();
            for (int i = 0; i < 9; i++) many.add(art("Outlet " + i, "Zelenskyy says Ukraine sank Russian warship off Libya", "Report " + i + " about the strike.", now - i * H));
            many.add(art("Reuters", "Kremlin accuses Ukraine of terrorism after ship strike", "Moscow responds.", now));
            many.add(art("Al Jazeera", "Libya's military says it is fully prepared", "Benghazi.", now));
            many.add(art("NPR", "Ukraine's war spreads to the Mediterranean", "Analysis of the strikes at sea.", now));
            many.add(art("Fox", "Ukrainian drones hit Russian ship near Benghazi port", "Drones hit a ship.", now));
            many.add(art("CBS", "Russia vows response to attack on its navy", "Response.", now));
            many.add(art("CNN", "What we know about the Libya drone attack", "Explainer.", now));
            StoryCluster big = story(many.toArray(new Article[0]));
            String bp = com.linkeddesk.ui.TestAccess.storyHtml(big, List.of(), 0);
            int shownRows = bp.split("Mute this outlet", -1).length - 1;
            c.that(shownRows == 5 && bp.contains("more articles…"), "five entries are shown first, then 'N more articles…' (" + shownRows + ")");
            c.that(bp.contains("Same headline also at:"), "outlets with the same headline are folded into one entry");
            com.linkeddesk.ui.TestAccess.expandCoverage(big.id, true);
            String bp2 = com.linkeddesk.ui.TestAccess.storyHtml(big, List.of(), 0);
            c.that(bp2.split("Mute this outlet", -1).length - 1 == 7 && bp2.contains("Show fewer"), "expanding shows every report (9 identical headlines as one)");
            com.linkeddesk.ui.TestAccess.expandCoverage(big.id, false);

            // ------------------------------------------------------------------ UI-F15
            c.suite("Claude links hidden without an API key (UI-F15)");
            big.links.add(new StoryLink(big, q, 0.4, List.of("Senate")));
            com.linkeddesk.ui.TestAccess.claude(false);
            String off = com.linkeddesk.ui.TestAccess.storyHtml(big, List.of(), 0);
            c.that(!off.contains("explain:") && !off.contains("framing:") && !off.contains("combined:") && !off.contains("connect:") && off.contains("Claude features") && off.contains("are off"),
                    "with no key, a story page has no Claude links, just one note");
            com.linkeddesk.ui.TestAccess.claude(true);
            String on = com.linkeddesk.ui.TestAccess.storyHtml(big, List.of(), 0);
            c.that(on.contains("explain:") && on.contains("framing:") && on.contains("combined:") && on.contains("connect:") && !on.contains("are off"), "with a key they're all back");

            // ------------------------------------------------------------------ AI-F11 + UI-F18 + UI-F19 on real news
            ArticleStore store = new ArticleStore(home.resolve("data2"));
            store.load();
            List<Article> real = new ArrayList<>();
            long newest = Calibrator.bundled().items().stream().mapToLong(l -> l.article().published).max().getAsLong();
            long shift = now - 2 * H - newest;
            for (Calibrator.Labelled l : Calibrator.bundled().items()) {
                Article x = l.article();
                real.add(new Article(FeedParser.idFor(x.url), x.url, x.title, x.summary, x.source, x.feedCategory, x.published + shift, x.fetched + shift));
            }
            store.addAll(real);
            NewsService svc = new NewsService(store, Settings.load());
            var res = svc.analyze(7);

            c.suite("More sources in combined articles (AI-F11)");
            StoryCluster libya = res.stories().stream().filter(x -> x.headline.contains("Libya")).max(Comparator.comparingInt(StoryCluster::sourceCount)).get();
            var group = svc.linkedGroup(libya, 12);
            var picked = Explainer.select(group, Explainer.Level.ARTICLE.maxArticles, a -> "");
            c.that(picked.size() >= 20, "the Libya story and its links now give " + picked.size() + " articles (was 11)");
            Set<String> groupsSeen = new HashSet<>();
            boolean dupCopy = false;
            for (StoryCluster g : group) if (g.copies != null) for (var cp : g.copies) {
                long n = cp.articles().stream().filter(picked::containsValue).count();
                if (n > 1) dupCopy = true;
            }
            c.that(!dupCopy, "still one copy per wire report");

            c.suite("People and organization pages (UI-F18)");
            EntityPage ep = svc.entity("Zelenskyy");
            c.that(ep.stories().size() >= 2 && ep.articles() >= 5, "'Zelenskyy' gathers " + ep.stories().size() + " stories, " + ep.articles() + " articles from the archive");
            c.that(ep.stories().get(0).lastSeen >= ep.stories().get(ep.stories().size() - 1).lastSeen, "newest first");
            c.that(!ep.byMonth().isEmpty() && !ep.byDesk().isEmpty(), "with a timeline by month and the desks it appears on");
            c.that(ep.withNames().stream().noneMatch(e -> e.getKey().toLowerCase().contains("zelensk")), "names it appears with (not itself): " + ep.withNames().stream().limit(5).map(Map.Entry::getKey).toList());
            String eh = com.linkeddesk.ui.TestAccess.entityHtml(ep);
            c.that(eh.contains("<h1>Zelenskyy</h1>") && eh.contains("Over time") && eh.contains("watch:") && eh.contains("topic:"), "the page: timeline, watch it, add as a topic");
            String sp = com.linkeddesk.ui.TestAccess.storyHtml(libya, List.of(), 0);
            c.that(sp.contains("href='entity:"), "names in Key terms open their page");

            c.suite("Case files (UI-F19)");
            CaseFiles cf = svc.caseFiles();
            var gd = cf.create("Golden Dome contracts");
            String dupErr = "";
            try { cf.create("golden dome CONTRACTS"); } catch (IllegalArgumentException e) { dupErr = e.getMessage(); }
            c.that(dupErr.contains("already"), "no two case files with the same name");
            List<StoryCluster> five = res.stories().subList(0, 5);
            for (StoryCluster x : five) cf.add(gd.id(), x);
            c.that(!cf.add(gd.id(), five.get(0)), "a story is only added once");
            cf.setNote(gd.id(), "Track awards to Huntsville companies.\nSecond line.");
            cf.setItemNote(gd.id(), cf.get(gd.id()).items().get(1), "Check the dollar figure.");
            var reread = new CaseFiles(home.resolve("casefiles.txt")).load().get(gd.id());
            c.that(reread.items().size() == 5 && reread.note().contains("Second line.") && reread.items().get(1).note().equals("Check the dollar figure."), "case files, notes and stories survive a restart");
            c.that(cf.containing(five.get(2)).size() == 1, "the story page can say which case files a story is in");
            var doc = CaseFileDoc.build(reread, svc::caseArticles);
            String md = doc.toMarkdown();
            int links = md.split("\\]\\(https?://", -1).length - 1;
            int arts = 0;
            for (StoryCluster x : five) arts += Math.min(30, x.articles.size());
            c.that(md.startsWith("# Golden Dome contracts") && md.contains("Track awards to Huntsville") && md.contains("Note:") && md.contains("Check the dollar figure.") && links >= arts,
                    "exported as one briefing pack with notes and every source linked (" + links + " links for " + arts + " articles)");
            c.that(doc.toDocx().length > 3000, "…as a Word document");
            cf.remove(gd.id(), reread.items().get(0));
            c.eq(4, cf.get(gd.id()).items().size(), "a story can be taken out");
            cf.delete(gd.id());
            c.that(cf.all().isEmpty(), "and a case file deleted");

            // ------------------------------------------------------------------ UI-F20
            c.suite("Read aloud (UI-F20)");
            c.eq("Ukraine says it struck ships near Libya, a navy base.", Speech.clean("Ukraine says it struck ships near Libya [Reuters, Nov 24] · a navy base. more ›"), "citations, arrows and 'more' aren't read out");
            var cmd = Speech.command("Microsoft Zira Desktop", 2);
            if (Speech.windows()) c.that(cmd.get(0).equals("powershell.exe") && cmd.get(cmd.size() - 1).contains("SelectVoice('Microsoft Zira Desktop')") && cmd.get(cmd.size() - 1).contains("[Console]::In"),
                    "on Windows: the built-in voice, text passed on standard input");
            else c.that(cmd.get(0).equals("say") || cmd.get(0).equals("espeak"), "on other systems: say or espeak (" + cmd.get(0) + ")");
            String quote = String.join(" ", Speech.command("O'Brien", 0));
            c.that(!Speech.windows() || quote.contains("O''Brien"), "voice names are quoted safely");
        } finally {
            if (oldHome == null) System.clearProperty("linkeddesk.home"); else System.setProperty("linkeddesk.home", oldHome);
        }
    }
}
