package com.linkeddesk.ai;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.Coverage;
import com.linkeddesk.app.Settings;
import com.linkeddesk.export.ExplainerDoc;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.ExplainerStore;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** P9 (v1.9): Explain this subject (AI-F08). */
public final class P9Test {
    static final long H = 3_600_000L, DAY = 24 * H;
    static int seq;

    static Article art(String source, String url, String title, String summary, long published) {
        return new Article("p9-" + (seq++), url, title, summary, source, Category.WORLD, published, published);
    }

    static StoryCluster story(List<Article> as) {
        StoryCluster c = new StoryCluster("x" + (seq++));
        c.headline = as.get(0).title;
        for (Article a : as) c.add(a);
        c.copies = Coverage.copies(c.articles);
        c.recount();
        return c;
    }

    /** An explainer written from the "Russia" articles of the real-news sample; WATCH:1 deliberately cites the wrong article. */
    static final String GROUNDED = """
BOTTOM LINE:
- Drones have hit Russian ships far from Ukraine: a cargo ship in the Baltic near Finland, and ships at a port in Libya [S2][S13][S17].
- Ukraine says it carried out the Libya strikes; Finland's prime minister says the war is spilling over [S11][S19].
TIMELINE:
- Nov 8: Beijing confirms a Xi-Trump-Putin meeting is under discussion [S1]
- Nov 23: A Russian cargo ship catches fire after a drone strike in the Baltic; Finland tightens security [S2][S3]
- Nov 24: The ship, Sparta II, is still smouldering as Finland and Estonia help put out the blaze; its crew is evacuated [S5][S7]
- Nov 24: The damaged ship is towed toward Russian home waters [S6][S20]
- Nov 25: Zelenskyy says Ukrainian sea drones sank a Russian warship off Libya and hit a cargo ship [S13][S15]
- Nov 25: Ukraine's security service says seven drones were launched from a mothership [S19]
WHO:
- Volodymyr Zelenskyy: Ukraine's president, who announced the Libya strikes [S13][S17]
- Finland and Estonia: helped fight the fire and tightened security in the Baltic [S3][S4]
- Sparta II: the Russian cargo ship hit in the Baltic, on the US sanctions list and linked to the military [S9][S4]
- US intelligence chief: says Putin is not America's friend in Ukraine [S8][S16]
WHY IT MATTERS:
- The war at sea has spread from the Black Sea to the Baltic and the Mediterranean [S13]
- Strikes near Finland and Estonia bring the fighting close to NATO territory [S12][S11]
- Shipping insurance in the Baltic could rise (Claude's reading) [S9]
DISPUTED:
- Who struck the Baltic ship: one report says Ukrainian drones, others only say drones [S6][S2]
- Whether the Libya target was a warship or a cargo ship is reported differently [S17][S15]
WATCH:
- Russia's response at sea and any talks between Xi, Trump and Putin [S1][S13]
- A new Polish case says Russia tried to assassinate a Ukrainian rapper [S20]
TERMS:
- Sea drone: a small uncrewed boat carrying explosives, steered remotely
- Mothership: a larger vessel that carries and launches the drones
""";

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        c.suite("Explain this subject: choosing articles (AI-F08)");
        String ap = "WASHINGTON (AP) — The Pentagon awarded the first Golden Dome interceptor contracts on Monday, splitting the work between three companies, officials said.";
        StoryCluster s1 = story(List.of(
                art("Chicago Tribune", "https://t.com/1", "Pentagon awards Golden Dome contracts", ap, now - 3 * DAY),
                art("Seattle Times", "https://s.com/1", "Golden Dome contracts awarded", ap, now - 3 * DAY + H),
                art("Breaking Defense", "https://bd.com/1", "Inside the Golden Dome awards", "Our reporting on which companies won and the program's timeline, with budget figures for next year.", now - 3 * DAY + 2 * H),
                art("The Guardian", "https://g.com/commentisfree/1", "Golden Dome is a fantasy", "A columnist argues the shield cannot work.", now - 2 * DAY)));
        StoryCluster s2 = story(List.of(
                art("Defense News", "https://dn.com/2", "Golden Dome test planned for spring", "The Missile Defense Agency plans a first test of the space-based interceptor next spring.", now - DAY),
                art("SpaceNews", "https://sn.com/2", "Space interceptor test date set", "A first flight test is set for spring at Vandenberg.", now - DAY + H)));
        StoryCluster s3 = story(List.of(art("WHNT", "https://whnt.com/3", "Huntsville jobs from Golden Dome", "Redstone Arsenal will manage part of the program, adding jobs in Huntsville.", now - 5 * H)));
        var refs = Explainer.select(List.of(s1, s2, s3), 25, a -> "");
        Set<String> urls = new HashSet<>();
        for (Article a : refs.values()) urls.add(a.url);
        c.that(urls.contains("https://bd.com/1") && urls.contains("https://whnt.com/3") && urls.contains("https://dn.com/2") && urls.contains("https://sn.com/2"), "every story and outlet is used");
        c.that(urls.contains("https://t.com/1") ^ urls.contains("https://s.com/1"), "only one of the two copies of the AP report is sent");
        c.that(urls.contains("https://g.com/commentisfree/1"), "an opinion piece is included when there's room");
        List<Article> order = new ArrayList<>(refs.values());
        boolean oldestFirst = true;
        for (int i = 1; i < order.size(); i++) if (order.get(i).published < order.get(i - 1).published) oldestFirst = false;
        c.that(oldestFirst && refs.containsKey("S1") && refs.containsKey("S" + refs.size()), "articles are numbered S1… oldest first");
        var three = Explainer.select(List.of(s1, s2, s3), 3, a -> "");
        c.eq(3, three.size(), "the limit is kept");
        c.that(three.values().stream().noneMatch(Coverage::opinion), "with little room, reporting comes before opinion");
        c.that(three.values().stream().map(a -> a.url).anyMatch(u -> u.contains("whnt")) && three.values().stream().map(a -> a.url).anyMatch(u -> u.contains("dn.com") || u.contains("sn.com")),
                "with little room, each story still gets its best report first");
        c.eq("strait-of-hormuz", Explainer.key("  Strait of  Hormuz! "), "subjects that differ only in spacing and case are the same explainer");
        c.eq(Explainer.key("Café"), Explainer.key("cafe"), "…and accents");

        c.suite("Explain this subject: prompt and answer (AI-F08)");
        String p = Explainer.prompt("Golden Dome", refs, a -> a.url.contains("bd.com") ? "Full report text from the Breaking Defense feed." : "", Explainer.Level.DETAILED, null);
        c.that(p.contains("Explain this subject") && p.contains("Golden Dome") && p.contains("[S1]") && p.contains("BOTTOM LINE:") && p.contains("TERMS:"), "the prompt names the subject, the sections and numbered articles");
        c.that(p.contains("· OPINION") && p.contains("never state them as fact"), "opinion pieces are marked as commentary");
        c.that(p.contains("Text: Full report text"), "a detailed explainer includes feed full text");
        c.that(!p.contains("CHANGED:"), "no 'what changed' section the first time");
        String q = Explainer.prompt("Golden Dome", refs, a -> "Full text", Explainer.Level.QUICK, null);
        c.that(q.contains("one screen") && !q.contains("Text: Full text"), "a quick explainer is shorter and skips full text");
        String reply = """
                **BOTTOM LINE:**
                - The Pentagon has awarded the first Golden Dome contracts and plans a test in spring [S1][S4].
                - It is a big program. 
                TIMELINE:
                1. Nov 21: Contracts awarded to three companies [S1][S2]
                2. Nov 23: Spring test date set [S4] [S5]
                3. Nov 24: Huntsville jobs announced [S77]
                WHO:
                - Missile Defense Agency: runs the test [S4]
                WHY IT MATTERS:
                - It could bring jobs to Huntsville [S6] (Claude's reading)
                DISPUTED:
                - A columnist argues the shield cannot work [S3]
                WATCH:
                - The spring test [S4]
                TERMS:
                - Interceptor: a missile that destroys an incoming missile
                """;
        var sources = Explainer.sources(refs);
        var r = Explainer.parse("Golden Dome", Explainer.Level.DETAILED, now, reply, sources, 0, List.of());
        c.eq(1, r.sections().get("BOTTOM LINE").size(), "a bottom-line point with no source is left out");
        c.eq(2, r.sections().get("TIMELINE").size(), "a timeline entry citing only an article that wasn't sent is left out");
        c.that(r.sections().get("TIMELINE").get(0).startsWith("Nov 21: Contracts") && r.sections().get("TIMELINE").get(1).equals("Nov 23: Spring test date set [S4] [S5]"),
                "numbered lines and bold headings are read: " + r.sections().get("TIMELINE"));
        c.that(r.sections().get("WHY IT MATTERS").get(0).contains("(Claude's reading)"), "Claude's own reading is kept and labelled");
        c.eq(1, r.sections().get("TERMS").size(), "terms don't need a source");
        c.that(r.problems().stream().anyMatch(x -> x.contains("S77")) && r.problems().stream().anyMatch(x -> x.contains("2 points without a source")), "removals are noted: " + r.problems());
        c.that(r.cited().containsAll(List.of("S1", "S2", "S4", "S5", "S3", "S6")), "cited articles are recorded");
        c.eq(refs.size(), r.refs().size(), "the articles read are kept with it");
        String withPrev = Explainer.prompt("Golden Dome", refs, a -> "", Explainer.Level.DETAILED,
                new Explainer.Result("Golden Dome", Explainer.Level.DETAILED, now - 2 * DAY, sources, r.sections(), List.of(), Set.of(), reply, 0, List.of()));
        c.that(withPrev.contains("CHANGED:") && withPrev.contains("EARLIER EXPLAINER") && withPrev.contains("NEW SINCE THE EARLIER EXPLAINER"),
                "an update asks what changed and marks the newer articles");
        c.that(!withPrev.substring(withPrev.indexOf("EARLIER EXPLAINER"), withPrev.indexOf("ARTICLES (oldest")).contains("[S"), "the earlier explainer's citations aren't sent (they'd point at the wrong articles)");
        c.eq("A [S1] and B.", Explainer.clean("A [S1] and B [S40].", sources), "follow-up answers lose citations to articles that weren't sent");
        String withInterests = Explainer.prompt("Golden Dome", refs, a -> "", Explainer.Level.DETAILED, null, List.of("Redstone Arsenal", "Hypersonic weapons"));
        c.that(withInterests.contains("The reader follows: Redstone Arsenal; Hypersonic weapons") && withInterests.contains("don't force"), "the reader's topics and watches are given for WHY IT MATTERS");
        c.that(p.contains("at most 650 words") && q.contains("at most 250 words"), "length limits keep a detailed explainer under 3 minutes");

        c.suite("Explain this subject: saving, export, phone (AI-F08)");
        Path dir = Files.createTempDirectory("ld-expl");
        ExplainerStore es = new ExplainerStore(dir);
        var withF = r.withFollowUp(new Explainer.FollowUp("More simply", "Line one [S1]\n- tab\there"));
        es.save(withF);
        var back = ExplainerStore.read(es.file(withF));
        c.that(back.subject().equals("Golden Dome") && back.created() == now && back.refs().equals(withF.refs()) && back.sections().equals(withF.sections()),
                "an explainer is saved and read back the same");
        c.eq(withF.followUps(), back.followUps(), "with its follow-ups (new lines and tabs kept)");
        es.save(new Explainer.Result("golden dome", Explainer.Level.QUICK, now + 1000, sources, r.sections(), List.of(), Set.of(), reply, now, List.of()));
        es.save(new Explainer.Result("Hormuz", Explainer.Level.QUICK, now + 2000, sources, r.sections(), List.of(), Set.of(), reply, 0, List.of()));
        Files.writeString(dir.resolve("broken.txt"), "not an explainer");
        c.eq(3, es.all().size(), "all saved explainers are listed (a broken file is skipped)");
        c.eq(now + 2000, es.all().get(0).created(), "newest first");
        c.eq(now + 1000, es.latest("Golden  DOME").created(), "the latest of a subject is found whatever its spelling");
        es.delete(es.all().get(0));
        c.eq(2, es.all().size(), "and can be deleted");
        var doc = ExplainerDoc.build(back);
        String md = doc.toMarkdown();
        c.that(md.contains("# Golden Dome") && md.contains("## What happened") && md.contains("(https://dn.com/2)") && md.contains("## More simply"),
                "exported with sections, linked citations and follow-ups");
        c.that(doc.toDocx().length > 2000, "as a Word document too");
        String page = com.linkeddesk.ui.TestAccess.explainerHtml(back);
        c.that(page.contains("href='https://dn.com/2'") && page.contains("deeper:TIMELINE:0") && page.contains("simpler:") && page.contains("Articles Claude read"),
                "the page links each citation, offers 'more' on each point and 'more simply'");
        c.that(page.contains("class='mine'"), "Claude's own reading is set apart");
        var marked = back.withWeak(Set.of("TIMELINE:1"));
        es.save(marked);
        c.eq(Set.of("TIMELINE:1"), ExplainerStore.read(es.file(marked)).weak(), "source-check marks are saved with the explainer");
        String mpage = com.linkeddesk.ui.TestAccess.explainerHtml(marked);
        c.that(mpage.contains("⚠ check source") && mpage.contains("Source check: ") && mpage.contains("min read"), "the page shows the source check, marks the point and the reading time");
        var b = new com.linkeddesk.analysis.Briefing(now, new LinkedHashMap<>(), List.of(), List.of(), 0, 0, 0,
                new com.linkeddesk.analysis.Analyzer.Result(List.of(), List.of(), 0));
        String phone = com.linkeddesk.export.PhonePage.build(b, null, com.linkeddesk.analysis.Regions.defaults(), Map.of(), List.of(), false, List.of(back));
        c.that(phone.contains("id=\"explainers\"") && phone.contains("Golden Dome") && phone.contains("href=\"https://dn.com/2\""), "recent explainers go into the phone briefing");

        // ------------------------------------------------------------------ the service, against a stand-in Claude
        c.suite("Explain this subject: with Claude (AI-F08)");
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> last = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            hits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            last.set(body);
            String text = body.contains("Rewrite this explanation") ? "In short: it's simple [S1] [S60]."
                    : body.contains("wants more on this point") ? "More detail here [S2]."
                    : "BOTTOM LINE:\n- Ukraine says it hit Russian ships [S1][S2].\n" + (body.contains("CHANGED:") ? "CHANGED:\n- New strikes reported [S3].\n" : "")
                      + "TIMELINE:\n- Nov 20: First strike [S1]\nWHO:\n- Zelenskyy: Ukraine's president [S2]\nWHY IT MATTERS:\n- Shipping risk [S3]\nWATCH:\n- Russia's reply [S1]\nTERMS:\n- USV: an uncrewed boat";
            String rep = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + ClaudeClient.json(text)
                    + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":9000,\"output_tokens\":900}}";
            byte[] bb = rep.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bb.length);
            ex.getResponseBody().write(bb);
            ex.close();
        });
        server.start();
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-p9");
        System.setProperty("linkeddesk.home", home.toString());
        System.setProperty("linkeddesk.apiBase", "http://127.0.0.1:" + server.getAddress().getPort());
        Files.writeString(home.resolve(".feeds-v1.1"), "t"); Files.writeString(home.resolve(".feeds-v1.8"), "t");
        try {
            Settings st = Settings.load();
            st.setApiKey("good"); st.setModel("claude-sonnet-5-5"); st.setClaudeBudget(5.0); st.save();
            ArticleStore store = new ArticleStore(home.resolve("data"));
            store.load();
            List<Article> real = new ArrayList<>();
            long newest = Calibrator.bundled().items().stream().mapToLong(l -> l.article().published).max().getAsLong();
            long shift = now - 2 * H - newest;
            for (Calibrator.Labelled l : Calibrator.bundled().items()) {
                Article x = l.article();
                real.add(new Article(FeedParser.idFor(x.url), x.url, x.title, x.summary, x.source, x.feedCategory, x.published + shift, x.fetched + shift));
            }
            store.addAll(real);
            NewsService svc = new NewsService(store, st);
            // The P9 gate on real news: 20+ articles over 3+ days, and every point traced to its articles.
            var russia = svc.planExplainer("Russia", null, Explainer.Level.DETAILED);
            Set<String> rdays = new HashSet<>();
            for (Article x : russia.refs().values()) rdays.add(java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(x.published), java.time.ZoneId.systemDefault()).toString());
            c.that(russia.articles() >= 20 && rdays.size() >= 3, "on the real-news sample, a broad subject gathers " + russia.articles() + " articles over " + rdays.size() + " days");
            // GROUNDED was written against these articles; renumber its citations for the current selection.
            String[][] wrote = {{"The Guardian", "Ukraine war briefing: Beijing"}, {"Reuters", "Russian cargo ship hit by drones in Baltic, Finland"},
                    {"USA TODAY", "Cargo ship bound for Russia"}, {"CNN", "Russian ship hit by drones in Baltic still smoulders"},
                    {"CNBC", "Russian ship hit by drones in Baltic still smolders"}, {"Barron's", "Russian Cargo Ship Hit By Ukrainian Drone Towed"},
                    {"The Yeshiva World", "BALTIC FIRE"}, {"Reuters", "US intelligence chief says Putin"}, {"Yahoo", "Drones hit Kaliningrad-bound"},
                    {"Independent Journal Review", "Drone Strike Disables"}, {"The Guardian", "Ukraine war briefing: Russian cargo ship"},
                    {"NPR", "Ukraine war spilling"}, {"Politico Europe", "Ukraine sank Russian warship"}, {"Al Jazeera", "Poland says Russia"},
                    {"Euronews", "Ukraine responsible for drone strikes"}, {"hromadske", "US intelligence chief"}, {"Chicago Tribune", "Ukrainian drones hit Russian warship"},
                    {"Al Jazeera", "Child rescued"}, {"The Guardian", "Ukraine confirms"}, {"Daily Tribune", "Cargo ship hit by drone towed"}};
            Map<String, String> renumber = new HashMap<>();
            for (int i = 0; i < wrote.length; i++)
                for (var e : russia.refs().entrySet())
                    if (e.getValue().source.equals(wrote[i][0]) && e.getValue().title.startsWith(wrote[i][1])) renumber.put("S" + (i + 1), e.getKey());
            c.eq(20, renumber.size(), "the 20 articles the test explainer was written from are all still chosen");
            java.util.regex.Matcher rm = Explainer.TAG.matcher(GROUNDED);
            StringBuilder gsb = new StringBuilder();
            while (rm.find()) rm.appendReplacement(gsb, "[" + renumber.getOrDefault("S" + rm.group(1), "S999") + "]");
            String grounded = rm.appendTail(gsb).toString();
            var gr = Explainer.parse("Russia", Explainer.Level.DETAILED, now, grounded, Explainer.sources(russia.refs()), 0, List.of());
            java.util.function.Function<String, String> textOf = id -> { Article x = russia.refs().get(id); return x == null ? "" : x.title + " " + x.summary; };
            var weak = Explainer.weakPoints(gr, textOf);
            c.eq(Set.of("WATCH:1"), weak, "source check: of " + gr.citedPoints() + " points written from these articles, only the one citing the wrong article is marked");
            c.that(gr.readingMinutes() <= 3, "a full explainer reads in under 3 minutes (" + gr.words() + " words, about " + gr.readingMinutes() + " min)");
            int flagged = 0, total = 0;
            for (String wrong : List.of("S14", "S1")) {
                var sw = Explainer.parse("Russia", Explainer.Level.DETAILED, now, GROUNDED.replaceAll("\\[S\\d+\\](\\[S\\d+\\])?", "[" + renumber.get(wrong) + "]"), Explainer.sources(russia.refs()), 0, List.of());
                flagged += Explainer.weakPoints(sw, textOf).size(); total += sw.citedPoints();
            }
            c.that(flagged * 100 >= total * 80, "pointing every citation at an unrelated article gets " + flagged + " of " + total + " points marked");
            var plan = svc.planExplainer("Ukraine Russian warship", null, Explainer.Level.DETAILED);
            c.that(plan.articles() >= 5 && plan.articles() <= 25, "the archive is searched for the subject: " + plan.articles() + " articles from " + plan.outlets() + " outlets");
            c.that(plan.refs().values().stream().allMatch(a -> (a.title + " " + a.summary).toLowerCase().matches("(?s).*(ukrain|russia|warship|zelensk|ship).*")), "…and they are about it");
            c.that(plan.estimate() > 0.005 && plan.estimate() < 0.15 && !plan.cached() && plan.previous() == null, String.format("the cost is estimated before sending ($%.3f)", plan.estimate()));
            c.eq(0, hits.get(), "nothing is sent while planning");
            var res = svc.runExplainer(plan);
            c.that(hits.get() == 1 && res.sections().get("BOTTOM LINE").size() == 1 && res.sections().get("TERMS").size() == 1, "Claude's explainer is read");
            c.that(res.weak() != null && ExplainerStore.read(svc.explainers().file(res)).weak().equals(res.weak()), "the source check runs on each new explainer and is saved");
            c.eq(1, svc.explainers().all().size(), "and saved");
            c.that(svc.claude().byFeature().containsKey("explainer"), "its cost is logged under Explainers");
            var again = svc.planExplainer("Ukraine Russian warship", null, Explainer.Level.DETAILED);
            c.that(again.previous() != null && again.prompt().contains("CHANGED:"), "explaining the same subject again asks what changed since");
            var res2 = svc.runExplainer(again);
            c.that(res2.sections().get("CHANGED").size() == 1 && res2.previousCreated() == res.created(), "the update says what changed and which explainer it updates");
            var simpler = svc.explainMore(res2, null);
            c.that(simpler.followUps().size() == 1 && simpler.followUps().get(0).text().equals("In short: it's simple [S1]."), "'Explain it more simply' is added (bad citation removed)");
            var deeper = svc.explainMore(simpler, res2.sections().get("TIMELINE").get(0));
            c.that(deeper.followUps().size() == 2 && deeper.followUps().get(1).label().startsWith("More on: Nov 20: First strike") && last.get().contains("Headline:"),
                    "'more' on a point sends the articles again (found by id) and adds the answer");
            c.eq(2, ExplainerStore.read(svc.explainers().file(deeper)).followUps().size(), "follow-ups are saved with the explainer");
            String err = "";
            try { svc.planExplainer("Zanzibar zebra polo", null, Explainer.Level.QUICK); } catch (IllegalStateException e) { err = e.getMessage(); }
            c.that(err.contains("isn't enough"), "a subject with almost nothing saved is refused before Claude is asked: " + err);
            err = "";
            try { svc.planExplainer("  ", null, Explainer.Level.QUICK); } catch (IllegalArgumentException e) { err = e.getMessage(); }
            c.that(err.contains("Type a subject"), "an empty subject is refused");
            var seeded = svc.planExplainer("Golden Dome", List.of(s1, s2, s3), Explainer.Level.QUICK);
            c.that(seeded.refs().values().stream().allMatch(a -> a.id.startsWith("p9-")), "Explain this on a story, topic, watch or search uses those stories");
            int before = hits.get();
            st.reload(); st.setClaudeBudget(0.02); st.save();
            var dear = svc.planExplainer("Ukraine Russian ship drones", null, Explainer.Level.DETAILED);
            err = "";
            try { svc.runExplainer(dear); } catch (ClaudeService.BudgetException e) { err = e.getMessage(); }
            c.that(err.contains("budget") && hits.get() == before, "nothing is sent when it would pass the monthly budget");
            c.that(dear.spent() + dear.estimate() > dear.budget(), "…and the plan shows that beforehand");
            st.reload(); st.setClaudeBudget(5.0); st.save();
            var cachedPlan = svc.planExplainer("Golden Dome", List.of(s1, s2, s3), Explainer.Level.QUICK);
            svc.runExplainer(cachedPlan);
            c.that(svc.planExplainer("Golden Dome", List.of(s1, s2, s3), Explainer.Level.QUICK).prompt().contains("CHANGED:"), "a repeat of the same request becomes an update");
            c.that(svc.recentExplainers().size() == 2 && svc.recentExplainers().stream().map(Explainer.Result::key).distinct().count() == 2, "the phone briefing gets the newest explainer per subject");
        } finally {
            server.stop(0);
            System.clearProperty("linkeddesk.apiBase");
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }
}
