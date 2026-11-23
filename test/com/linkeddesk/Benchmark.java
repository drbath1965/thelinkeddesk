package com.linkeddesk;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.app.Settings;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * QA-F03 performance benchmark (run by hand: java -cp out:out-test com.linkeddesk.Benchmark 250000).
 * Generates N articles over ~6 months with news-like text (Zipf-distributed vocabulary, recurring names,
 * same story reported by several outlets), saves them through the real archive, then times start-up,
 * analysis and search.
 */
public final class Benchmark {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 250_000;
        Path home = Files.createTempDirectory("ld-bench");
        System.setProperty("linkeddesk.home", home.toString());
        Random r = new Random(42);
        String[] vocab = new String[6000];
        for (int i = 0; i < vocab.length; i++) vocab[i] = word(r, 4 + r.nextInt(6));
        String[] names = new String[800];
        for (int i = 0; i < names.length; i++) { String w = word(r, 5 + r.nextInt(5)); names[i] = Character.toUpperCase(w.charAt(0)) + w.substring(1); }
        String[] outlets = new String[120];
        for (int i = 0; i < outlets.length; i++) outlets[i] = "Outlet " + i;

        long now = System.currentTimeMillis(), span = 182L * 24 * 3600_000;
        ArticleStore store = new ArticleStore(home.resolve("data"));
        store.load();
        long t0 = System.nanoTime();
        List<Article> batch = new ArrayList<>();
        int id = 0;
        while (id < n) {
            // One event, reported by 1-6 outlets within a few hours.
            long when = now - (long) (r.nextDouble() * span);
            String a = names[zipf(r, names.length)], b = names[zipf(r, names.length)];
            StringBuilder core = new StringBuilder(a).append(' ');
            for (int k = 0; k < 4; k++) core.append(vocab[zipf(r, vocab.length)]).append(' ');
            core.append(b);
            int reports = 1 + r.nextInt(6);
            for (int k = 0; k < reports && id < n; k++, id++) {
                String title = core + " " + vocab[zipf(r, vocab.length)] + " " + vocab[zipf(r, vocab.length)];
                StringBuilder sum = new StringBuilder();
                for (int w = 0; w < 30; w++) sum.append(w % 9 == 0 ? names[zipf(r, names.length)] : vocab[zipf(r, vocab.length)]).append(' ');
                long t = when + r.nextInt(6 * 3600_000);
                batch.add(new Article("b" + id, "https://example.com/" + id, title, sum.toString().trim(), outlets[r.nextInt(outlets.length)],
                        Category.values()[r.nextInt(5)], t, Math.min(now, t + 3_600_000L)));
            }
            if (batch.size() >= 20_000) { store.addAll(batch); batch.clear(); }
        }
        store.addAll(batch);
        System.out.printf("Generated and saved %,d articles in %.1f s%n", store.size(), (System.nanoTime() - t0) / 1e9);
        long bytes = Files.list(home.resolve("data")).filter(p -> p.toString().endsWith(".tsv")).mapToLong(p -> p.toFile().length()).sum();
        System.out.printf("Archive on disk: %.0f MB in %d monthly files%n", bytes / 1e6, Files.list(home.resolve("data")).filter(p -> p.toString().endsWith(".tsv")).count());

        System.gc();
        long t1 = System.nanoTime();
        NewsService svc = new NewsService(new ArticleStore(home.resolve("data")), Settings.load());
        svc.loadAll();
        double startup = (System.nanoTime() - t1) / 1e9;
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long mem = rt.totalMemory() - rt.freeMemory();
        System.out.printf("Start-up (load archive): %.2f s — %,d articles indexed, %,d recent ones in memory, heap in use %.0f MB%n",
                startup, svc.store().size(), svc.store().loadedCount(), mem / 1e6);

        for (int days : new int[]{1, 7, 30}) {
            long t = System.nanoTime();
            Analyzer.Result res = svc.analyze(days);
            System.out.printf("Analysis, last %2d days: %.2f s — %,d articles -> %,d stories, %,d threads%n",
                    days, (System.nanoTime() - t) / 1e9, res.articleCount(), res.stories().size(), res.threads().size());
            if (days == 7) {
                long linked = res.stories().stream().filter(c -> !c.links.isEmpty()).count();
                for (int max : new int[]{500, 1000, 0}) {
                    long tm = System.nanoTime();
                    com.linkeddesk.analysis.GraphLayout g = com.linkeddesk.analysis.GraphLayout.build(res.stories(), c -> true, false, max);
                    System.out.printf("Connection map, 7 days, %s: %.2f s — %,d stories, %,d links (of %,d linked stories)%n",
                            max == 0 ? "all linked" : "up to " + max, (System.nanoTime() - tm) / 1e9, g.nodes.size(), g.edges.size(), linked);
                }
                long tw = System.nanoTime();
                var idx = com.linkeddesk.analysis.Countries.index(res.stories());
                System.out.printf("World map, 7 days: %.2f s — %,d countries%n", (System.nanoTime() - tw) / 1e9, idx.size());
                long tb = System.nanoTime();
                var b = svc.briefing();
                System.out.printf("Briefing: %.2f s — %,d stories in the last 24 h%n", (System.nanoTime() - tb) / 1e9, b.stories());
            }
        }
        for (String q : new String[]{names[3], names[3] + " " + names[10], "\"" + vocab[5] + " " + vocab[9] + "\"", names[200] + " OR " + names[300]}) {
            NewsService.SearchResult sr = svc.search(new SearchQuery(q), NewsService.SearchFilter.ANY);
            System.out.printf("Search all history [%s]: %.2f s — %,d matches%n", q, sr.millis() / 1000.0, sr.total());
        }
    }

    private static int zipf(Random r, int n) {
        double x = r.nextDouble();
        return Math.min(n - 1, (int) Math.floor(Math.pow(n + 1, x)) - 1);
    }

    private static String word(Random r, int len) {
        String c = "bcdfghjklmnprstvwz", v = "aeiou";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(i % 2 == 0 ? c.charAt(r.nextInt(c.length())) : v.charAt(r.nextInt(v.length())));
        return sb.toString();
    }
}
