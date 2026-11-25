package com.linkeddesk.ai;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.Coverage;
import com.linkeddesk.analysis.GraphLayout;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.ConnectionStore;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** P10 (v1.10): Find connections with Claude (AI-F09). */
public final class P10Test {
    static final long H = 3_600_000L, DAY = 24 * H;
    static int seq;

    static StoryCluster story(String headline, String summary, Category desk, long when, String... sources) {
        StoryCluster c = new StoryCluster("c" + (seq++));
        c.headline = headline;
        c.category = desk;
        for (String s : sources.length == 0 ? new String[]{"Reuters"} : sources)
            c.add(new Article("p10-" + (seq++), "https://ex.com/" + seq, headline, summary, s, desk, when, when));
        c.copies = Coverage.copies(c.articles);
        c.recount();
        c.keyTerms = new ArrayList<>();
        return c;
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        c.suite("Find connections with Claude: choosing candidates (AI-F09)");
        c.that(Connections.Kind.parse("cause and effect") == Connections.Kind.CAUSE && Connections.Kind.parse("Follow-up") == Connections.Kind.FOLLOWUP
                && Connections.Kind.parse("same people") == Connections.Kind.ACTORS && Connections.Kind.parse("market") == Connections.Kind.IMPACT
                && Connections.Kind.parse("weather") == null, "kinds are read in Claude's words too");
        StoryCluster target = story("Pentagon awards Golden Dome interceptor contracts", "The Missile Defense Agency picked Lockheed Martin and two others for Golden Dome.",
                Category.DEFENSE, now - 5 * H, "Reuters", "Defense News");
        StoryCluster linked = story("Golden Dome contracts: who won", "Lockheed Martin leads the Golden Dome awards.", Category.DEFENSE, now - 4 * H);
        target.links.add(new StoryLink(target, linked, 0.4, List.of("Golden Dome")));
        StoryCluster earlier = story("Golden Dome bids due", "Companies submit Golden Dome bids.", Category.DEFENSE, now - 5 * DAY);
        target.continues = earlier;
        StoryCluster lockheed = story("Lockheed Martin shares jump", "Shares of Lockheed Martin rose 6% on Wall Street.", Category.FINANCE, now - 3 * H);
        StoryCluster huntsville = story("Huntsville expects defense jobs boom", "The Missile Defense Agency's Huntsville offices will grow.", Category.POLITICS, now - 2 * H);
        StoryCluster unrelatedOther = story("Storm hits Philippines", "A typhoon made landfall on Luzon.", Category.SOCIETY, now - 6 * H);
        StoryCluster unrelatedSame = story("Army fitness test changes", "Soldiers face a new test.", Category.DEFENSE, now - 6 * H);
        StoryCluster oldOther = story("Wheat prices fall", "Grain futures dropped.", Category.FINANCE, now - 9 * DAY);
        StoryCluster rejected = story("Raytheon wins Golden Dome radar deal", "Raytheon's radar is part of Golden Dome.", Category.DEFENSE, now - 3 * H);
        List<StoryCluster> pool = List.of(target, linked, earlier, lockheed, huntsville, unrelatedOther, unrelatedSame, oldOther, rejected);
        var cands = Connections.candidates(target, pool, 40, (x, y) -> y == rejected, now);
        Set<StoryCluster> in = new HashSet<>();
        for (var k : cands) in.add(k.story());
        c.that(in.contains(lockheed) && in.contains(huntsville), "stories sharing names (Lockheed Martin, Missile Defense Agency) are candidates, on any desk");
        c.that(!in.contains(linked) && !in.contains(earlier) && !in.contains(target), "stories already linked, or in its storyline, aren't sent again");
        c.that(!in.contains(rejected), "a pair you rejected (or accepted) isn't asked about again");
        c.that(in.contains(unrelatedOther) && !in.contains(unrelatedSame) && !in.contains(oldOther), "recent stories on other desks are included for connections that share no words");
        c.that(cands.stream().filter(k -> k.story() == lockheed).findFirst().get().why().contains("lockheed martin"), "each candidate says why it was picked");
        List<StoryCluster> many = new ArrayList<>(pool);
        for (int i = 0; i < 80; i++) many.add(story("Lockheed Martin item " + i, "Lockheed Martin news " + i, i % 2 == 0 ? Category.FINANCE : Category.WORLD, now - i * H));
        var capped = Connections.candidates(target, many, 40, null, now);
        c.eq(40, capped.size(), "at most 40 candidates");
        c.that(capped.get(0).ref().equals("C1") && capped.get(39).ref().equals("C40"), "numbered C1…C40");

        c.suite("Find connections with Claude: asking and reading the answer (AI-F09)");
        String p = Connections.prompt(target, cands);
        c.that(p.contains("TARGET: Pentagon awards Golden Dome") && p.contains("[C1]") && p.contains("CAUSE, FOLLOWUP, ACTORS, PLACE, BACKGROUND, CONFLICT, IMPACT")
                && p.contains("don't share the same words") && p.contains("reply NONE"), "the prompt has the target, the candidates and the kinds of connection");
        String lc = cands.stream().filter(k -> k.story() == lockheed).findFirst().get().ref();
        String hv = cands.stream().filter(k -> k.story() == huntsville).findFirst().get().ref();
        String uo = cands.stream().filter(k -> k.story() == unrelatedOther).findFirst().get().ref();
        String reply = "**" + lc + " | IMPACT | HIGH | The contract award sent Lockheed Martin's shares up.**\n"
                + "[" + hv + "] - same place - Medium - The Missile Defense Agency is based in Huntsville, so the awards bring jobs there.\n"
                + uo + " | BACKGROUND | LOW | Both are news.\n"
                + "C99 | CAUSE | HIGH | Not a candidate.\n"
                + lc + " | CAUSE | HIGH | duplicate\n"
                + "C2 | WEATHER | HIGH | unknown kind\n";
        var r = Connections.parse(reply, target, cands);
        c.eq(2, r.found().size(), "two connections read (bold, brackets and dashes allowed)");
        c.that(r.found().get(0).candidate().story() == lockheed && r.found().get(0).kind() == Connections.Kind.IMPACT && r.found().get(0).confidence() == Connections.Confidence.HIGH,
                "with the kind and confidence");
        c.that(r.found().get(1).kind() == Connections.Kind.PLACE && r.found().get(1).reason().startsWith("The Missile Defense Agency is based in Huntsville"), "…and the reason");
        c.that(r.problems().stream().anyMatch(x -> x.contains("C99")) && r.problems().stream().anyMatch(x -> x.contains("low-confidence"))
                && r.problems().stream().anyMatch(x -> x.contains("couldn't be read")), "unknown stories, weak guesses and unreadable lines are dropped and noted: " + r.problems());
        c.eq(0, Connections.parse("NONE", target, cands).found().size(), "NONE means nothing found");

        c.suite("Find connections with Claude: suggestions and decisions (AI-F09)");
        Path dir = Files.createTempDirectory("ld-conn");
        ConnectionStore cs = new ConnectionStore(dir.resolve("connections.tsv"));
        c.eq(0L, cs.checked(target), "a story not checked yet");
        cs.record(r, now);
        c.eq(2, cs.suggestions(target).size(), "Claude's suggestions are saved on the story");
        c.eq(now, cs.checked(target), "with when it was checked");
        cs.record(Connections.parse("NONE", target, cands), now + 1000);
        c.that(cs.suggestions(target).isEmpty() && cs.checked(target) == now + 1000, "a later check that finds nothing replaces them (and is remembered)");
        cs.record(r, now + 2000);
        ConnectionStore again = new ConnectionStore(dir.resolve("connections.tsv"));
        var sugg = again.suggestions(target);
        c.eq(2, sugg.size(), "suggestions survive a restart");
        again.decide(sugg.get(0), true);
        again.decide(sugg.get(1), false);
        c.that(again.suggestions(target).isEmpty(), "decided suggestions leave the list");
        c.eq(ConnectionStore.Status.ACCEPTED, again.decision(target, lockheed), "accepted");
        c.eq(ConnectionStore.Status.REJECTED, again.decision(huntsville, target), "rejected (either way round)");
        c.that(again.accepted(target, lockheed) != null && again.accepted(target, huntsville) == null && again.accepted().size() == 1, "only the accepted one counts as a connection");
        StoryCluster regrouped = new StoryCluster("new");
        regrouped.add(target.articles.get(1));
        regrouped.add(new Article("p10-x", "https://ex.com/x", "Golden Dome follow-up", "", "AP", Category.DEFENSE, now, now));
        c.eq(ConnectionStore.Status.ACCEPTED, again.decision(regrouped, lockheed), "the decision still applies after the next download regroups the story");

        // ------------------------------------------------------------------ the service, against a stand-in Claude
        c.suite("Find connections with Claude: in the app (AI-F09)");
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> last = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            hits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            last.set(body);
            String text = "C1 | CAUSE | HIGH | The first led to the second.\nC2 | BACKGROUND | MEDIUM | Gives the background.";
            String rep = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + ClaudeClient.json(text)
                    + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":4000,\"output_tokens\":200}}";
            byte[] bb = rep.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bb.length);
            ex.getResponseBody().write(bb);
            ex.close();
        });
        server.start();
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-p10");
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
            StoryCluster t = res.stories().stream().max(Comparator.comparingInt(StoryCluster::sourceCount)).get();
            var plan = svc.planConnections(t, res.stories());
            Set<StoryCluster> tl = new HashSet<>();
            for (StoryLink l : t.links) tl.add(l.to());
            c.that(!plan.candidates().isEmpty() && plan.candidates().size() <= 40 && plan.candidates().stream().noneMatch(k -> tl.contains(k.story())),
                    "on real news: " + plan.candidates().size() + " candidates for \"" + t.headline + "\", none already linked");
            c.that(plan.estimate() > 0.001 && plan.estimate() < 0.05 && !plan.cached() && hits.get() == 0, String.format("the cost is shown before sending ($%.3f)", plan.estimate()));
            var found = svc.runConnections(plan);
            c.that(hits.get() == 1 && found.found().size() == 2 && svc.connections().suggestions(t).size() == 2, "Claude's connections are saved as suggestions on the story");
            c.that(svc.claude().byFeature().containsKey("connections"), "its cost is logged under Connections");
            StoryCluster first = found.found().get(0).candidate().story(), second = found.found().get(1).candidate().story();
            String page = com.linkeddesk.ui.TestAccess.storyHtml(t, svc.connections().suggestions(t), svc.connections().checked(t));
            c.that(page.contains("Connections Claude found (2)") && page.contains("Cause and effect") && page.contains("conn-accept:0") && page.contains("conn-reject:1")
                    && page.contains("The first led to the second."), "the story page lists them by kind with Accept and Reject");
            var sugg2 = svc.connections().suggestions(t);
            svc.decideConnection(sugg2.stream().filter(e -> e.kind() == Connections.Kind.CAUSE).findFirst().get(), true);
            svc.decideConnection(sugg2.stream().filter(e -> e.kind() == Connections.Kind.BACKGROUND).findFirst().get(), false);
            var res2 = svc.analyze(7);
            StoryCluster t2 = res2.stories().stream().filter(x -> x.articles.contains(t.articles.get(0))).findFirst().get();
            StoryLink made = t2.links.stream().filter(l -> l.to().articles.contains(first.articles.get(0))).findFirst().orElse(null);
            c.that(made != null && made.confirmed() && made.byClaude() && made.claudeKind().equals("Cause and effect") && made.claudeReason().equals("The first led to the second."),
                    "an accepted connection becomes one of the story's links, marked found by Claude");
            StoryCluster back = made.to();
            c.that(back.links.stream().anyMatch(l -> l.to() == t2 && l.byClaude()), "…in both directions");
            c.that(t2.links.stream().noneMatch(l -> l.to().articles.contains(second.articles.get(0))), "a rejected one doesn't become a link");
            var g = GraphLayout.build(res2.stories(), x -> true, true, 0);
            c.that(g.edges.stream().anyMatch(GraphLayout.Edge::byClaude), "it's on the connection map (drawn in gold)");
            String page2 = com.linkeddesk.ui.TestAccess.storyHtml(t2, svc.connections().suggestions(t2), svc.connections().checked(t2));
            c.that(page2.contains("Found by Claude · Cause and effect:") && page2.contains("You accepted this connection"), "the link says Claude found it, and why");
            var plan2 = svc.planConnections(t2, res2.stories());
            c.that(plan2.candidates().stream().noneMatch(k -> k.story().articles.contains(second.articles.get(0)) || k.story().articles.contains(first.articles.get(0))),
                    "looking again doesn't ask about pairs you've decided");
            int before = hits.get();
            int daily = svc.dailyConnections(res2, 2);
            c.that(daily == 2 && hits.get() == before + 2, "the daily run checks the top new stories it hasn't checked (" + daily + ")");
            int daily2 = svc.dailyConnections(res2, 2);
            c.that(daily2 <= 2 && hits.get() == before + 2 + daily2, "and the next run moves on to others");
            st.reload(); st.setClaudeBudget(0.0001); st.save();
            int b4 = hits.get();
            String err = "";
            try { svc.runConnections(svc.planConnections(t2, res2.stories())); } catch (ClaudeService.BudgetException e) { err = e.getMessage(); }
            c.that(err.contains("budget") && hits.get() == b4, "nothing is sent when it would pass the monthly budget");
            st.reload(); st.setConnectionsDaily(3); st.save();
            c.eq(3, Settings.load().connectionsDaily(), "the daily setting is kept");
        } finally {
            server.stop(0);
            System.clearProperty("linkeddesk.apiBase");
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }
}
