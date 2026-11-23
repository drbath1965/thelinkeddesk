package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Measures how well grouping and linking work, and finds the thresholds that work best.
 *
 * Two kinds of evidence:
 * <ul>
 *   <li>A labelled sample of real news shipped with the app (labelled-week.tsv): every article carries the
 *       event it reports, so grouping and links can be scored exactly.</li>
 *   <li>Your own verdicts (Right / Wrong on links, "Not this story" on articles), scored on your archive.</li>
 * </ul>
 * Precision: of the groupings/links made, the share that are right. Recall: of the right ones, the share made.
 */
public final class Calibrator {
    public static final double TARGET_GROUP_PRECISION = 0.90;
    public static final double TARGET_LINK_PRECISION = 0.80;
    static final double PER_DESK = 0.06;

    /** A labelled article: the event it reports ("topic.event"). */
    public record Labelled(Article article, String label) {
        public String topic() { int d = label.indexOf('.'); return d < 0 ? label : label.substring(0, d); }
    }

    /** A labelled set: articles plus which topics are related to each other. */
    public record Sample(List<Labelled> items, Set<String> related) {
        public List<Article> articles() { return items.stream().map(Labelled::article).toList(); }
        public boolean related(String t1, String t2) { return t1.equals(t2) || related.contains(t1 + "|" + t2) || related.contains(t2 + "|" + t1); }
    }

    public record Metrics(int groupPairs, int groupCorrect, int goldPairs, int links, int linksCorrect, int relatedStories,
                          int relatedLinked, int storylineSteps, int storylineCorrect, int stories) {
        public double groupPrecision() { return groupPairs == 0 ? 1 : (double) groupCorrect / groupPairs; }
        public double groupRecall() { return goldPairs == 0 ? 1 : (double) groupCorrect / goldPairs; }
        public double linkPrecision() { return links == 0 ? 1 : (double) linksCorrect / links; }
        public double linkRecall() { return relatedStories == 0 ? 1 : (double) relatedLinked / relatedStories; }
        public double storylinePrecision() { return storylineSteps == 0 ? 1 : (double) storylineCorrect / storylineSteps; }
        public boolean meetsTargets() { return groupPrecision() >= TARGET_GROUP_PRECISION && linkPrecision() >= TARGET_LINK_PRECISION; }
        public String summary() {
            return String.format("grouping precision %.0f%% (%d/%d pairs), recall %.0f%%; links precision %.0f%% (%d/%d), recall %.0f%%; storylines %d/%d right; %d stories",
                    100 * groupPrecision(), groupCorrect, groupPairs, 100 * groupRecall(), 100 * linkPrecision(), linksCorrect, links,
                    100 * linkRecall(), storylineCorrect, storylineSteps, stories);
        }
    }

    /** Scores of your own verdicts under some thresholds (verdicts themselves not applied). */
    public record VerdictScore(int rightKept, int right, int wrongShown, int wrong, int splitsKept, int splits) {
        public int judged() { return right + wrong + splits; }
        public double linkPrecision() { int shown = rightKept + wrongShown; return shown == 0 ? 1 : (double) rightKept / shown; }
        public String summary() {
            if (judged() == 0) return "no verdicts yet";
            return String.format("%d of %d links you marked right are made; %d of %d you marked wrong would be made without your verdict; %d of %d articles you took out would be grouped wrongly again",
                    rightKept, right, wrongShown, wrong, splitsKept, splits);
        }
    }

    // ------------------------------------------------------------------ the labelled sample

    /** Reads the labelled sample shipped inside the app. */
    public static Sample bundled() throws IOException {
        try (InputStream in = Calibrator.class.getResourceAsStream("/com/linkeddesk/analysis/labelled-week.tsv")) {
            if (in == null) throw new IOException("labelled sample not found in the app");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static Sample parse(String text) {
        List<Labelled> items = new ArrayList<>();
        Set<String> related = new HashSet<>();
        int n = 0;
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p[0].equals("related") && p.length >= 3) { related.add(p[1] + "|" + p[2]); continue; }
            if (p.length < 5) continue;
            long t = LocalDateTime.parse(p[2]).toInstant(ZoneOffset.UTC).toEpochMilli();
            Category desk = switch (p[1]) {
                case "F" -> Category.FINANCE; case "P" -> Category.POLITICS; case "S" -> Category.SOCIETY;
                case "T" -> Category.TECH; default -> Category.WORLD;
            };
            String id = "L" + (++n);
            Article a = new Article(id, "https://example.org/labelled/" + n, p[4], p.length > 5 ? p[5] : "", p[3], desk, t, t);
            items.add(new Labelled(a, p[0]));
        }
        return new Sample(items, related);
    }

    public static Metrics evaluate(Sample s, Analyzer.Options opt) {
        Analyzer.Result r = Analyzer.analyze(s.articles(), opt.with(opt.thresholds()));
        Map<String, String> label = new HashMap<>();
        for (Labelled l : s.items()) label.put(l.article().id, l.label());

        // Grouping, scored on pairs of articles.
        int pairs = 0, correct = 0;
        for (StoryCluster c : r.stories()) {
            List<Article> as = c.articles;
            for (int i = 0; i < as.size(); i++)
                for (int j = i + 1; j < as.size(); j++) {
                    pairs++;
                    if (label.get(as.get(i).id).equals(label.get(as.get(j).id))) correct++;
                }
        }
        Map<String, Integer> perLabel = new HashMap<>();
        for (Labelled l : s.items()) perLabel.merge(l.label(), 1, Integer::sum);
        int gold = 0;
        for (int k : perLabel.values()) gold += k * (k - 1) / 2;

        // Links, scored on pairs of stories by the event most of their articles report.
        Map<StoryCluster, String> main = new IdentityHashMap<>();
        for (StoryCluster c : r.stories()) {
            Map<String, Integer> v = new HashMap<>();
            for (Article a : c.articles) v.merge(label.get(a.id), 1, Integer::sum);
            main.put(c, v.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey());
        }
        int links = 0, linksOk = 0;
        Set<String> linked = new HashSet<>();
        for (StoryCluster c : r.stories()) {
            for (StoryLink l : c.links) {
                if (System.identityHashCode(c) > System.identityHashCode(l.to())) continue;
                links++;
                linked.add(System.identityHashCode(c) + "|" + System.identityHashCode(l.to()));
                if (s.related(topic(main.get(c)), topic(main.get(l.to())))) linksOk++;
            }
        }
        // Recall: stories that have a related story (a different event on a related topic) within the link
        // window, and the share of them that got at least one right link.
        int relatedPairs = 0, relatedLinked = 0;
        List<StoryCluster> st = r.stories();
        for (StoryCluster a : st) {
            boolean has = false;
            for (StoryCluster b : st) {
                if (a == b || main.get(a).equals(main.get(b))) continue;
                if (!s.related(topic(main.get(a)), topic(main.get(b)))) continue;
                if (Math.abs(a.lastSeen - b.lastSeen) > Analyzer.LINK_WINDOW_MS) continue;
                has = true;
                break;
            }
            if (!has) continue;
            relatedPairs++;
            for (StoryLink l : a.links) if (s.related(topic(main.get(a)), topic(main.get(l.to())))) { relatedLinked++; break; }
        }
        int steps = 0, stepsOk = 0;
        for (StoryCluster c : r.stories()) {
            if (c.continues == null) continue;
            steps++;
            if (topic(main.get(c)).equals(topic(main.get(c.continues)))) stepsOk++;
        }
        return new Metrics(pairs, correct, gold, links, linksOk, relatedPairs, relatedLinked, steps, stepsOk, r.stories().size());
    }

    private static String topic(String label) { int d = label.indexOf('.'); return d < 0 ? label : label.substring(0, d); }

    // ------------------------------------------------------------------ your verdicts

    /** How your verdicts fare under these thresholds, without the verdicts themselves being applied. */
    public static VerdictScore evaluate(List<Article> archive, Feedback fb, Analyzer.Options opt) {
        if (fb.isEmpty()) return new VerdictScore(0, 0, 0, 0, 0, 0);
        Analyzer.Result r = Analyzer.analyze(archive, new Analyzer.Options(opt.lexicon(), opt.thresholds(), Feedback.NONE, opt.desks()));
        Map<String, StoryCluster> storyOf = new HashMap<>();
        for (StoryCluster c : r.stories()) for (Article a : c.articles) storyOf.put(a.id, c);
        int right = 0, rightKept = 0, wrong = 0, wrongShown = 0, splits = 0, splitsKept = 0;
        for (Feedback.Entry e : fb.entries()) {
            if (e.kind() == Feedback.Kind.SPLIT) {
                StoryCluster c = null;
                for (String id : e.a()) c = storyOf.get(id);
                if (c == null) continue;
                splits++;
                for (Article a : c.articles) if (e.b().contains(a.id)) { splitsKept++; break; }
                continue;
            }
            Set<StoryCluster> sa = stories(e.a(), storyOf), sb = stories(e.b(), storyOf);
            if (sa.isEmpty() || sb.isEmpty()) continue;
            boolean isLinked = false;
            for (StoryCluster x : sa) for (StoryLink l : x.links) if (sb.contains(l.to())) isLinked = true;
            for (StoryCluster x : sa) if (sb.contains(x)) isLinked = true; // grouped together counts as linked
            if (e.kind() == Feedback.Kind.RIGHT) { right++; if (isLinked) rightKept++; }
            else { wrong++; if (isLinked) wrongShown++; }
        }
        return new VerdictScore(rightKept, right, wrongShown, wrong, splitsKept, splits);
    }

    private static Set<StoryCluster> stories(Set<String> ids, Map<String, StoryCluster> storyOf) {
        Set<StoryCluster> s = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : ids) { StoryCluster c = storyOf.get(id); if (c != null) s.add(c); }
        return s;
    }

    // ------------------------------------------------------------------ finding the best thresholds

    public record Suggestion(Thresholds thresholds, Metrics sample, VerdictScore yours, double score) {}

    /**
     * Searches thresholds that meet the precision targets on the labelled sample and keep as much recall as
     * possible, also rewarding links you marked right and penalizing links you marked wrong.
     * {@code archive} may be empty when you have no verdicts.
     */
    public static Suggestion tune(Sample sample, List<Article> archive, Analyzer.Options base, Consumer<String> progress, BooleanSupplier cancelled) {
        Feedback fb = base.feedback();
        Thresholds best = base.thresholds();
        double bestScore = score(sample, archive, fb, base, best);
        double[] sameGrid = range(0.24, 0.52, 0.02), linkGrid = range(0.06, 0.30, 0.02), lineGrid = range(0.20, 0.50, 0.05);
        int step = 0, total = sameGrid.length + linkGrid.length + lineGrid.length + Category.values().length * 14;
        // 1) all desks together, then 2) each desk on its own, keeping a change only if it scores better.
        for (int round = 0; round < 2; round++) {
            if (round == 0) {
                for (double v : sameGrid) { if (cancelled.getAsBoolean()) break; progress.accept(pct(++step, total)); best = tryIt(best.withAll(v, best.link(Category.WORLD)), best, sample, archive, fb, base); }
                for (double v : linkGrid) { if (cancelled.getAsBoolean()) break; progress.accept(pct(++step, total)); best = tryIt(best.withAll(best.sameEvent(Category.WORLD), v), best, sample, archive, fb, base); }
                for (double v : lineGrid) { if (cancelled.getAsBoolean()) break; progress.accept(pct(++step, total)); best = tryIt(best.withLineage(v), best, sample, archive, fb, base); }
            } else {
                // Each desk may move a little from the shared value (at most ±0.06), so a desk with few labelled
                // stories can't drift to an extreme that only fits a handful of examples.
                double same0 = best.sameEvent(Category.WORLD), link0 = best.link(Category.WORLD);
                for (Category c : Category.values()) {
                    for (double v : range(same0 - PER_DESK, same0 + PER_DESK, 0.02)) { if (cancelled.getAsBoolean()) break; progress.accept(pct(++step, total)); best = tryIt(best.withSameEvent(c, v), best, sample, archive, fb, base); }
                    for (double v : range(Math.max(0.02, link0 - PER_DESK), link0 + PER_DESK, 0.02)) { if (cancelled.getAsBoolean()) break; progress.accept(pct(++step, total)); best = tryIt(best.withLink(c, v), best, sample, archive, fb, base); }
                }
            }
        }
        bestScore = score(sample, archive, fb, base, best);
        Analyzer.Options o = base.with(best);
        return new Suggestion(best, evaluate(sample, new Analyzer.Options(o.lexicon(), best, Feedback.NONE, DeskRules.NONE)),
                evaluate(archive, fb, o), bestScore);
    }

    private static String pct(int step, int total) { return "Trying settings… " + Math.min(99, 100 * step / total) + "%"; }

    private static double[] range(double from, double to, double by) {
        int n = (int) Math.round((to - from) / by) + 1;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Math.round((from + i * by) * 1000) / 1000.0;
        return out;
    }

    private static Thresholds tryIt(Thresholds cand, Thresholds cur, Sample s, List<Article> archive, Feedback fb, Analyzer.Options base) {
        double a = score(s, archive, fb, base, cand), b = score(s, archive, fb, base, cur);
        return a > b + 1e-9 ? cand : cur;
    }

    private static final Map<String, Double> CACHE = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Double> e) { return size() > 2000; }
    });

    /** Balanced score; settings that miss a precision target score far lower. */
    static double score(Sample s, List<Article> archive, Feedback fb, Analyzer.Options base, Thresholds t) {
        String key = System.identityHashCode(s) + "/" + System.identityHashCode(archive) + "/" + System.identityHashCode(fb) + "/" + t;
        Double hit = CACHE.get(key);
        if (hit != null) return hit;
        Metrics m = evaluate(s, new Analyzer.Options(base.lexicon(), t, Feedback.NONE, DeskRules.NONE));
        double score = f(m.groupPrecision(), m.groupRecall()) + f(m.linkPrecision(), m.linkRecall()) + 0.25 * m.storylinePrecision();
        if (m.groupPrecision() < TARGET_GROUP_PRECISION) score -= 2 + 10 * (TARGET_GROUP_PRECISION - m.groupPrecision());
        if (m.linkPrecision() < TARGET_LINK_PRECISION) score -= 2 + 10 * (TARGET_LINK_PRECISION - m.linkPrecision());
        if (!fb.isEmpty() && !archive.isEmpty()) {
            VerdictScore v = evaluate(archive, fb, base.with(t));
            // Each verdict counts: right links kept, wrong links avoided, wrongly grouped articles kept apart.
            if (v.judged() > 0) score += 2.0 * (v.rightKept() + (v.wrong() - v.wrongShown()) + (v.splits() - v.splitsKept())) / v.judged();
        }
        // Prefer staying near the defaults when scores tie.
        score -= 0.001 * distance(t);
        CACHE.put(key, score);
        return score;
    }

    private static final Thresholds SHIPPED = Thresholds.defaults();

    private static double distance(Thresholds t) {
        double d = Math.abs(t.lineage() - SHIPPED.lineage());
        for (Category c : Category.values())
            d += Math.abs(t.sameEvent(c) - SHIPPED.sameEvent(c)) + Math.abs(t.link(c) - SHIPPED.link(c));
        return d;
    }

    /** F1 score (precision and recall equally; the precision targets are enforced separately). */
    private static double f(double p, double r) {
        double b2 = 1.0;
        return p + r == 0 ? 0 : (1 + b2) * p * r / (b2 * p + r);
    }
}
