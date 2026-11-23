package com.linkeddesk.ai;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P4 Claude features against a local stand-in for the Anthropic API: budget, usage and cache (AI-F05),
 * daily digest (AI-F02), link checks (AI-F03) and questions across the archive (AI-F04), on the real-news sample.
 */
public final class ClaudeFeaturesTest {
    public static void run(Check c) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> lastPrompt = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            hits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastPrompt.set(body);
            String text;
            if (body.contains("morning digest")) {
                text = "REPORTED:\n- Ukraine says its naval drones sank a Russian warship off Libya [S1][S2].\n- Belgium's strike grounded flights [S3].\n"
                        + "INFERENCE:\n- The strikes at sea suggest the war is spreading beyond the Black Sea [S1][S99].\n"
                        + "WATCH:\n- Russia's response in the Mediterranean [S1].";
            } else if (body.contains("A news app linked these pairs")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= 50; i++) {
                    if (!body.contains("L" + i + " (shared")) break;
                    sb.append("L").append(i).append(" | ").append(i == 1 ? "REAL" : i == 2 ? "COINCIDENCE" : "UNSURE")
                      .append(" | ").append(i == 1 ? "Both describe Ukraine's naval drone campaign against Russian ships." : "Only a shared name.").append('\n');
                }
                text = sb.toString();
            } else if (body.contains("QUESTION:")) {
                text = "Belgium's national strike against austerity shut its airports [S1][S2]. Inference: more strikes may follow [S40].";
            } else {
                text = "Real link.";
            }
            String reply = "{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":"
                    + ClaudeClient.json(text) + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1000,\"output_tokens\":200}}";
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-claude");
        System.setProperty("linkeddesk.home", home.toString());
        System.setProperty("linkeddesk.apiBase", "http://127.0.0.1:" + server.getAddress().getPort());
        Files.writeString(home.resolve(".feeds-v1.1"), "test"); Files.writeString(home.resolve(".feeds-v1.8"), "test");
        try {
            Settings st = Settings.load();
            st.setApiKey("good");
            st.setModel("claude-sonnet-5-5");
            st.setClaudeBudget(5.0);
            st.save();

            // ------------------------------------------------------------------ AI-F05
            c.suite("Claude cost controls (AI-F05)");
            ClaudeService cs = new ClaudeService(st, home);
            c.that(java.util.Arrays.equals(new double[]{2, 10}, cs.prices()), "Claude Sonnet 5.5 priced at the list price, $2 / $10 per million tokens");
            String a1 = cs.ask("explain", "Is the link real?", 300);
            c.eq(1, hits.get(), "a question goes to Claude");
            c.that(Math.abs(cs.spentThisMonth() - 0.004) < 1e-9, String.format("its cost is logged from the tokens Anthropic reports ($%.4f for 1,000 in + 200 out)", cs.spentThisMonth()));
            String a2 = cs.ask("explain", "Is the link real?", 300);
            c.that(hits.get() == 1 && a1.equals(a2) && cs.cacheHits() == 1, "the same question again is answered from the cache (not sent, not paid)");
            ClaudeService restarted = new ClaudeService(st, home);
            restarted.ask("explain", "Is the link real?", 300);
            c.eq(1, hits.get(), "the cache survives a restart (claude-cache.tsv)");
            c.that(restarted.summary().startsWith("This month: $0.00 of $5.00") && restarted.summary().contains("1 request"), "usage summary: " + restarted.summary());
            st.setClaudeBudget(0.005);
            st.save();
            String msg = "";
            try { cs.ask("explain", "A different, longer question about two stories " + "x".repeat(3000), 700); }
            catch (ClaudeService.BudgetException e) { msg = e.getMessage(); }
            c.that(hits.get() == 1 && msg.startsWith("Monthly Claude budget reached: $0.00 of $0.01"), "a request that could pass the budget isn't sent, with a clear message: " + msg);
            st.setClaudeBudget(0);
            st.save();
            try { cs.ask("explain", "x", 10); msg = ""; } catch (ClaudeService.BudgetException e) { msg = e.getMessage(); }
            c.that(msg.contains("switched off"), "budget $0 switches Claude off");
            st.setClaudeBudget(5.0);
            st.setModel("claude-future-9");
            st.save();
            c.that(cs.prices()[0] == 8 && cs.prices()[1] == 40, "an unknown model is priced at the highest common tier, to be safe");
            st.setPrices(1.5, 7.5);
            st.save();
            c.that(cs.prices()[0] == 1.5 && cs.prices()[1] == 7.5, "your own prices in Settings are used");
            st.setPrices(0, 0);
            st.setModel("claude-sonnet-5-5");
            st.save();
            c.that(cs.byFeature().get("explain")[0] == 1, "usage per feature (Tools > Claude usage)");

            // ------------------------------------------------------------------ the archive for the rest: real news, moved to the last week
            ArticleStore store = new ArticleStore(home.resolve("data"));
            store.load();
            List<Article> real = new ArrayList<>();
            long newest = Calibrator.bundled().items().stream().mapToLong(l -> l.article().published).max().getAsLong();
            long shift = System.currentTimeMillis() - 3_600_000L - newest;
            for (Calibrator.Labelled l : Calibrator.bundled().items()) {
                Article x = l.article();
                real.add(new Article(FeedParser.idFor(x.url), x.url, x.title, x.summary, x.source, x.feedCategory, x.published + shift, x.fetched + shift));
            }
            store.addAll(real);
            NewsService svc = new NewsService(store, st);
            Briefing b = svc.briefing();

            // ------------------------------------------------------------------ AI-F02
            c.suite("Claude daily digest (AI-F02)");
            int before = hits.get();
            Digest d = svc.writeDigest(b, false);
            c.that(hits.get() == before + 1 && lastPrompt.get().contains("[S1]") && lastPrompt.get().contains("REPORTED:"),
                    "the briefing's top stories are sent as [S1], [S2]… with the REPORTED / INFERENCE / WATCH format");
            c.that(d.reported().size() == 2 && d.inference().size() == 1 && d.watch().size() == 1, "reported facts, inference and things to watch are kept apart");
            c.that(d.problems().stream().anyMatch(p -> p.contains("S99")) && !d.inference().get(0).contains("S99"), "a citation to a story that wasn't sent ([S99]) is removed and noted");
            boolean allFound = true;
            for (String line : d.reported()) for (String id : Cite.cited(line)) allFound &= Cite.find(d.refs().get(id), b.result().stories()) != null;
            c.that(allFound, "every citation in the digest opens a story in today's briefing");
            c.that(Files.exists(Digest.file(home, java.time.LocalDate.now())), "saved as digests/" + java.time.LocalDate.now() + ".txt");
            Digest same = svc.writeDigest(b, false);
            c.that(hits.get() == before + 1 && same.reported().equals(d.reported()), "written once a day: opening the briefing again doesn't ask again");
            Digest reread = Digest.load(home, java.time.LocalDate.now());
            c.that(reread.refs().equals(d.refs()) && reread.inference().equals(d.inference()), "today's digest reads back after a restart, with its story references");
            Briefing later = svc.briefing();
            StoryCluster s1 = Cite.find(reread.refs().get("S1"), later.result().stories());
            c.that(s1 != null, "after regrouping, [S1] still finds its story (" + (s1 == null ? "-" : s1.headline) + ")");

            // ------------------------------------------------------------------ AI-F03
            c.suite("Claude checks the top links (AI-F03)");
            before = hits.get();
            int n = svc.checkLinks(b, 3);
            c.that(n == 3 && hits.get() == before + 1, "the day's top 3 new links are checked in one request (" + n + " verdicts)");
            List<StoryLink> checked = new ArrayList<>();
            for (StoryCluster s : b.result().stories()) for (StoryLink l : s.links) if (svc.linkChecks().find(s, l.to()) != null) checked.add(l);
            c.that(checked.stream().anyMatch(l -> svc.linkChecks().find(l.from(), l.to()).verdict() == LinkChecks.Verdict.REAL)
                    && checked.stream().anyMatch(l -> svc.linkChecks().find(l.from(), l.to()).verdict() == LinkChecks.Verdict.COINCIDENCE),
                    "verdicts stored: real and likely coincidence, with a reason");
            c.that(Files.readString(home.resolve("link-checks.tsv")).contains("REAL"), "kept in link-checks.tsv");
            c.that(new LinkChecks(home).load().size() == 3, "verdicts read back after a restart");
            before = hits.get();
            int again = svc.checkLinks(b, 3);
            String prompt2 = lastPrompt.get();
            boolean resent = false;
            for (StoryLink l : checked) resent |= hits.get() > before && prompt2.contains(l.from().headline) && prompt2.contains(l.to().headline);
            c.that(!resent, "checked links are never sent again (" + again + " other links checked next)");
            StoryLink real1 = checked.stream().filter(l -> svc.linkChecks().find(l.from(), l.to()).verdict() == LinkChecks.Verdict.REAL).findFirst().get();
            String page = com.linkeddesk.ui.TestAccess.storyPage(real1.from(), svc);
            c.that(page.contains("Checked by Claude: real"), "the verdict shows on the link in the story page");
            List<LinkChecks.Check> parsed = LinkChecks.parse("**L2 | coincidence | only a name**\nnoise\nL9 | REAL | out of range", List.of(real1, real1), "m", 0);
            c.that(parsed.size() == 1 && parsed.get(0).verdict() == LinkChecks.Verdict.COINCIDENCE, "answer lines are read leniently; lines for pairs not sent are ignored");

            // ------------------------------------------------------------------ AI-F04
            c.suite("Ask questions across the archive (AI-F04)");
            before = hits.get();
            ArchiveQA.Answer ans = svc.askArchive("What is behind Belgium's strike?");
            c.that(hits.get() == before + 1, "the question is sent with the matching stories");
            c.that(ans.refs().values().stream().anyMatch(s -> s.headline.contains("Belgi")), "the Belgian strike story is among the stories found");
            boolean exist = true;
            for (String id : ans.cited()) for (Article a : ans.refs().get(id).articles) exist &= store.get(a.id) != null;
            c.that(!ans.cited().isEmpty() && exist, "every citation in the answer is a story whose articles are in the archive (" + ans.cited() + ")");
            c.that(!ans.text().contains("S40") && ans.problems().stream().anyMatch(p -> p.contains("S40")), "a citation to a story that wasn't sent ([S40]) is removed and noted");
            c.that(ans.refs().size() <= ArchiveQA.MAX_STORIES, "at most " + ArchiveQA.MAX_STORIES + " stories sent (" + ans.refs().size() + ")");
            before = hits.get();
            ArchiveQA.Answer none = svc.askArchive("Flibbertigibbet zzqx quorble?");
            c.that(none.nothingFound() && hits.get() == before, "nothing matching: Claude isn't asked (no cost)");
            int[] searched = {0};
            List<StoryCluster> week = svc.findForQuestion("Ebola this week", searched);
            c.that(!week.isEmpty() && week.stream().allMatch(s -> s.lastSeen >= System.currentTimeMillis() - 8 * 86_400_000L), "'this week' keeps to the last 7 days");
            List<StoryCluster> fed = svc.findForQuestion("What did the WHO say about Congo?", searched);
            c.that(fed.stream().anyMatch(s -> s.headline.contains("Ebola")), "names and aliases in the question find the stories (WHO, Congo -> Ebola stories)");
            String html = com.linkeddesk.ui.TestAccess.answerPage(ans);
            c.that(html.contains("href='story:") && html.contains("Stories Claude was given") && (html.contains(" · cited")), "the answer page links each citation and lists the stories Claude was given");

            // ------------------------------------------------------------------ daily run
            c.suite("Digest and checks after the daily download (AI-F02/F03)");
            Files.deleteIfExists(Digest.file(home, java.time.LocalDate.now()));
            String daily = svc.dailyClaude();
            c.that(daily.startsWith("Digest written.") && daily.contains("links checked") && daily.contains("This month:"), "daily run: " + daily);
            st.setClaudeBudget(0.001);
            st.save();
            Files.deleteIfExists(Digest.file(home, java.time.LocalDate.now()));
            before = hits.get();
            String capped = svc.dailyClaude();
            c.that(hits.get() == before && svc.todaysDigest() != null, "over budget, a digest already paid for still comes from the cache (free): " + capped);
            String refused = "";
            try { svc.askArchive("What is happening with Ebola in Congo?"); } catch (ClaudeService.BudgetException e) { refused = e.getMessage(); }
            c.that(hits.get() == before && refused.startsWith("Monthly Claude budget reached"), "over budget, a new request is refused with the reason");
        } finally {
            server.stop(0);
            System.clearProperty("linkeddesk.apiBase");
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }
}
