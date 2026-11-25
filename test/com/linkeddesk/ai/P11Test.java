package com.linkeddesk.ai;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.app.Settings;
import com.linkeddesk.export.ExplainerDoc;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
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

/** P11 (v1.11): one combined article from linked stories (AI-F10). */
public final class P11Test {
    static final long H = 3_600_000L, DAY = 24 * H;

    static final String REPLY = """
            HEADLINE: Ukraine takes its sea-drone war from the Baltic to the Mediterranean
            SUMMARY: Ukraine says its sea drones struck Russian ships off Libya, days after a drone hit a Russian cargo ship in the Baltic [S3][S1]. Finland says the war is spilling over [S2].
            ARTICLE:
            Ukraine says seven sea drones launched from a mothership struck two Russian ships at a port in Libya [S3][S4].

            The strike came days after drones hit the Russian cargo ship Sparta II in the Baltic near Finland, setting it on fire [S1][S2].

            This paragraph has no source at all.

            Finland's prime minister says the conflict is spilling over [S2] [S9]. Shipping insurers may raise rates (Claude's reading) [S1].
            """;

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        c.suite("One article from linked stories: writing and reading it (AI-F10)");
        Map<String, Article> refs = new LinkedHashMap<>();
        refs.put("S1", new Article("a1", "https://ex.com/1", "Russian cargo ship hit by drones in Baltic", "A Russian cargo ship, the Sparta II, caught fire after a drone strike near Finland.", "Reuters", Category.WORLD, now - 2 * DAY, now - 2 * DAY));
        refs.put("S2", new Article("a2", "https://ex.com/2", "Finland PM says conflict spilling over", "Finland's prime minister said the war is spilling over after the Baltic drone strike.", "The Guardian", Category.WORLD, now - DAY, now - DAY));
        refs.put("S3", new Article("a3", "https://ex.com/3", "Ukraine sank Russian warship off Libya", "Kyiv said seven sea drones struck Russian ships at a port in Libya.", "Politico Europe", Category.WORLD, now - 5 * H, now - 5 * H));
        refs.put("S4", new Article("a4", "https://ex.com/4", "Drones launched from a mothership", "Ukraine's security service says seven drones were launched from a mothership.", "The Guardian", Category.WORLD, now - 4 * H, now - 4 * H));
        String p = Explainer.prompt("Zelenskyy says Ukraine behind drone attacks near Libya", refs, a -> "", Explainer.Level.ARTICLE, null);
        c.that(p.contains("Write ONE news article") && p.contains("Show how the stories connect") && p.contains("HEADLINE:") && p.contains("ARTICLE:")
                && p.contains("every paragraph must cite") && p.contains("At most 700 words") && p.contains("[S4] The Guardian"), "the prompt asks for one article that connects the stories, every paragraph cited");
        var r = Explainer.parse("Zelenskyy says Ukraine behind drone attacks near Libya", Explainer.Level.ARTICLE, now, REPLY, Explainer.sources(refs), 0, List.of());
        c.eq("Ukraine takes its sea-drone war from the Baltic to the Mediterranean", Explainer.headline(r), "Claude's headline is read");
        c.eq(1, r.sections().get("SUMMARY").size(), "the summary");
        c.eq(3, r.sections().get("ARTICLE").size(), "the paragraphs; one with no source is left out");
        c.that(r.problems().stream().anyMatch(x -> x.contains("S9")) && r.problems().stream().anyMatch(x -> x.contains("1 point without a source")), "and both removals are noted: " + r.problems());
        c.that(r.sections().get("ARTICLE").get(2).contains("(Claude's reading)"), "Claude's own reading stays marked");
        c.that(r.readingMinutes() <= 3, "a short read (" + r.words() + " words)");
        var weak = Explainer.weakPoints(r, id -> { Article a = refs.get(id); return a == null ? "" : a.title + " " + a.summary; });
        c.that(weak.isEmpty(), "source check passes paragraphs written from their articles: " + weak);
        Map<String, Article> withFed = new LinkedHashMap<>(refs);
        withFed.put("S5", new Article("a5", "https://ex.com/5", "Fed holds interest rates steady", "The Federal Reserve kept its benchmark rate unchanged and signalled patience on cuts.", "CNBC", Category.FINANCE, now, now));
        var miscited = Explainer.parse("x", Explainer.Level.ARTICLE, now, REPLY.replace("setting it on fire [S1][S2]", "setting it on fire [S5]"), Explainer.sources(withFed), 0, List.of());
        var weak2 = Explainer.weakPoints(miscited, id -> { Article a = withFed.get(id); return a == null ? "" : a.title + " " + a.summary; });
        c.eq(Set.of("ARTICLE:1"), weak2, "a paragraph citing an unrelated article is marked (a related one on the same subject would not be: the hand check still matters)");
        var prev = r;
        String up = Explainer.prompt("Zelenskyy says Ukraine behind drone attacks near Libya", refs, a -> "", Explainer.Level.ARTICLE,
                new Explainer.Result(prev.subject(), prev.level(), now - 3 * DAY, prev.refs(), prev.sections(), List.of(), Set.of(), REPLY, 0, List.of()));
        c.that(up.contains("An earlier version of this article") && up.contains("start with \"Since") && up.contains("NEW SINCE THE EARLIER ARTICLE"), "an update leads with what's new since the last version");

        c.suite("One article from linked stories: showing, saving, sharing (AI-F10)");
        String page = com.linkeddesk.ui.TestAccess.explainerHtml(r);
        c.that(page.contains("COMBINED ARTICLE BY CLAUDE") && page.contains("<h1>Ukraine takes its sea-drone war") && page.contains("class='para'")
                && page.contains("href='https://ex.com/3'") && page.contains("deeper:ARTICLE:0") && !page.contains("Detailed version"), "the article page: headline, cited paragraphs, 'more' on each");
        String md = ExplainerDoc.build(r).toMarkdown();
        c.that(md.startsWith("# Ukraine takes its sea-drone war") && md.contains("(https://ex.com/1)") && md.contains("## Articles Claude read"), "exported as an article");
        Path dir = Files.createTempDirectory("ld-art");
        ExplainerStore es = new ExplainerStore(dir);
        es.save(r);
        var back = ExplainerStore.read(es.file(r));
        c.that(back.level() == Explainer.Level.ARTICLE && Explainer.headline(back).equals(Explainer.headline(r)) && back.sections().equals(r.sections()), "saved and read back as an article");
        var b = new com.linkeddesk.analysis.Briefing(now, new LinkedHashMap<>(), List.of(), List.of(), 0, 0, 0, new com.linkeddesk.analysis.Analyzer.Result(List.of(), List.of(), 0));
        String phone = com.linkeddesk.export.PhonePage.build(b, null, com.linkeddesk.analysis.Regions.defaults(), Map.of(), List.of(), false, List.of(back));
        c.that(phone.contains("Ukraine takes its sea-drone war") && phone.contains("Read the article") && phone.contains("href=\"https://ex.com/4\""), "it goes into the phone briefing with its headline");
        StoryCluster lone = new StoryCluster("lone");
        lone.headline = "Alone"; lone.category = Category.WORLD;
        lone.add(refs.get("S1"));
        StoryCluster other = new StoryCluster("o");
        other.headline = "Other"; other.category = Category.WORLD;
        other.add(refs.get("S3"));
        c.that(!com.linkeddesk.ui.TestAccess.storyHtml(lone, List.of(), 0).contains("combined:"), "a story with no links doesn't offer it");
        lone.links.add(new StoryLink(lone, other, 0.3, List.of("Russia")));
        String sp = com.linkeddesk.ui.TestAccess.storyHtml(lone, List.of(), 0);
        c.that(sp.contains("href='combined:'") && sp.contains("Write one article from this story and its 1 linked story"), "a linked story offers 'Write one article from this story and its linked stories'");

        // ------------------------------------------------------------------ in the app, against a stand-in Claude
        c.suite("One article from linked stories: in the app (AI-F10)");
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> last = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            hits.incrementAndGet();
            last.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String rep = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + ClaudeClient.json(REPLY)
                    + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":9000,\"output_tokens\":900}}";
            byte[] bb = rep.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bb.length);
            ex.getResponseBody().write(bb);
            ex.close();
        });
        server.start();
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-p11");
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
            var res = svc.analyze(7);
            StoryCluster t = res.stories().stream().max(Comparator.comparingInt((StoryCluster x) -> x.links.size())).get();
            var group = svc.linkedGroup(t, 12);
            Set<StoryCluster> linked = new HashSet<>();
            for (StoryLink l : t.links) linked.add(l.to());
            Set<StoryCluster> seen = new HashSet<>();
            for (StoryCluster x = t.continues; x != null && seen.add(x); x = x.continues) linked.add(x);
            c.that(group.get(0) == t && group.size() >= 3 && group.size() <= 12 && group.subList(1, group.size()).stream().allMatch(linked::contains),
                    "the group is the story and its linked stories (" + group.size() + " for \"" + t.headline + "\")" + (group.get(0) == t ? "" : " first≠t") + group.stream().filter(x -> x != t && !linked.contains(x)).map(x -> " extra:" + x.headline).toList());
            var plan = svc.planExplainer(t.headline, group, Explainer.Level.ARTICLE);
            Set<StoryCluster> from = new HashSet<>();
            for (Article a : plan.refs().values()) for (StoryCluster g : group) if (g.articles.contains(a)) from.add(g);
            c.that(plan.articles() >= 6 && plan.articles() <= 30 && from.size() >= 3, "on real news: " + plan.articles() + " articles from " + from.size() + " of the linked stories");
            c.that(plan.prompt().contains("Write ONE news article") && plan.estimate() > 0.01 && plan.estimate() < 0.15 && hits.get() == 0,
                    String.format("the cost is shown before sending ($%.3f)", plan.estimate()));
            var art = svc.runExplainer(plan);
            c.that(hits.get() == 1 && art.level() == Explainer.Level.ARTICLE && Explainer.headline(art).startsWith("Ukraine takes") && svc.explainers().all().size() == 1,
                    "the article is written and saved with the explainers");
            int wrote = svc.dailyCombined(res, 2);
            c.that(wrote >= 1 && wrote <= 2 && hits.get() == 1 + wrote, "the daily option writes articles for the top linked stories (" + wrote + ")");
            Set<String> firstSubjects = new HashSet<>(), firstIds = new HashSet<>();
            for (var x : svc.explainers().all()) { firstSubjects.add(x.key()); for (var src : x.refs().values()) firstIds.add(src.id()); }
            int before2 = svc.explainers().all().size();
            svc.dailyCombined(res, 2);
            var added = svc.explainers().all().subList(0, svc.explainers().all().size() - before2);
            c.that(added.stream().noneMatch(x -> firstSubjects.contains(x.key())), "the next run doesn't write the same stories again the same day");
            st.reload(); st.setCombinedDaily(2); st.save();
            c.eq(2, Settings.load().combinedDaily(), "the daily setting is kept");
            c.that(svc.recentExplainers().stream().anyMatch(x -> x.level() == Explainer.Level.ARTICLE), "articles go into the phone briefing");
        } finally {
            server.stop(0);
            System.clearProperty("linkeddesk.apiBase");
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }
}
