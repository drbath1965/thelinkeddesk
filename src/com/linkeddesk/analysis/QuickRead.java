package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick read without Claude (ANL-F15): two to four sentences the outlets themselves wrote, picked so they cover the
 * facts most outlets agree on without repeating each other. Nothing is reworded; each sentence is credited to the
 * outlet that wrote it and says how many others report the same thing. Opinion pieces and cut-off sentences are
 * left out; the same wire report counts once.
 */
public final class QuickRead {
    private QuickRead() {}

    public record Line(String text, Article from, int alsoReported) {}

    private static final Pattern DATELINE = Pattern.compile("^(?:By [\\p{L} .'-]{3,40}?\\s+)?(?:[A-Z][A-Z .'-]{2,30}(?:,\\s*[A-Za-z]{3,9}\\.?\\s*\\d{1,2})?\\s*(?:\\([A-Za-z .]{2,20}\\))?\\s*[-–—:]+\\s*)");
    private static final Pattern SENT = Pattern.compile("[^.!?]+(?:(?<=\\b(?:Mr|Mrs|Ms|Dr|St|U\\.S|U\\.K|U\\.N|E\\.U|Gen|Lt|Col|Sen|Rep|Gov|Jan|Feb|Aug|Sept|Oct|Nov|Dec|No|vs|Inc|Corp|Co|Ltd|[A-Z]))\\.[^.!?]*)*[.!?]+[\"'”’)]*");
    private static final Set<String> STOP = com.linkeddesk.analysis.Text.STOP;

    public static List<Line> of(StoryCluster c) { return of(c, a -> ""); }

    /** The quick read of a story with 2+ outlets (empty otherwise). */
    public static List<Line> of(StoryCluster c, Function<Article, String> fullText) {
        if (c == null || c.sourceCount() < 2) return List.of();
        // One text per independent report: copies of the same wire story count once.
        Map<Article, String> groupOf = new HashMap<>();
        if (c.copies != null) for (Coverage.Copies cp : c.copies) for (Article a : cp.articles()) groupOf.put(a, cp.label() + "#" + cp.articles().get(0).id);
        record Cand(String text, Article from, Set<String> words) {}
        List<Cand> cands = new ArrayList<>();
        Map<String, Set<String>> wordsByUnit = new LinkedHashMap<>();
        Set<String> seenText = new HashSet<>();
        for (Article a : c.articlesNewestFirst()) {
            if (Coverage.opinion(a)) continue;
            String unit = groupOf.getOrDefault(a, "o:" + a.source);
            String body = a.summary == null ? "" : a.summary;
            String full = fullText.apply(a);
            if (full != null && full.length() > body.length()) body = body + " " + full.substring(0, Math.min(1200, full.length()));
            Set<String> unitWords = wordsByUnit.computeIfAbsent(unit, k -> new HashSet<>());
            unitWords.addAll(words(a.title));
            for (String s : sentences(body)) {
                Set<String> w = words(s);
                unitWords.addAll(w);
                if (w.size() < 4 || s.length() < 40 || s.length() > 320) continue;
                if (!seenText.add(norm(s))) continue;
                cands.add(new Cand(s, a, w));
            }
        }
        if (cands.isEmpty()) return List.of();
        int units = wordsByUnit.size();
        // How many independent reports carry most of a sentence's words: agreement.
        Map<Cand, Integer> agree = new HashMap<>();
        Map<Cand, Double> score = new HashMap<>();
        for (Cand k : cands) {
            int n = 0;
            for (Set<String> uw : wordsByUnit.values()) {
                int hit = 0;
                for (String w : k.words()) if (uw.contains(w)) hit++;
                if (hit >= Math.ceil(k.words().size() * 0.5)) n++;
            }
            agree.put(k, n);
            double numbers = k.text().matches(".*\\d.*") ? 0.3 : 0;
            score.put(k, n / (double) units + numbers + Math.min(1, k.words().size() / 12.0) * 0.2);
        }
        List<Cand> order = new ArrayList<>(cands);
        order.sort(Comparator.comparingDouble((Cand k) -> score.get(k)).reversed());
        int want = c.sourceCount() >= 8 ? 4 : c.sourceCount() >= 4 ? 3 : 2;
        List<Cand> pick = new ArrayList<>();
        Set<String> outlets = new HashSet<>();
        for (Cand k : order) {
            if (pick.size() >= want) break;
            boolean repeats = false;
            for (Cand p : pick) if (jaccard(p.words(), k.words()) > 0.35) { repeats = true; break; }
            if (repeats) continue;
            if (outlets.contains(k.from().source) && order.size() > want * 2 && pick.size() < want - 1) continue;   // spread the credit when there's choice
            pick.add(k);
            outlets.add(k.from().source);
        }
        List<Line> out = new ArrayList<>();
        for (Cand k : pick) out.add(new Line(k.text(), k.from(), Math.max(0, agree.get(k) - 1)));
        return out;
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        String t = text.replaceAll("\\s+", " ").strip();
        if (t.endsWith("…") || t.endsWith("...") || t.endsWith("[…]")) {          // cut off by the feed: drop the last part
            int cut = Math.max(t.lastIndexOf(". "), Math.max(t.lastIndexOf("! "), t.lastIndexOf("? ")));
            t = cut < 0 ? "" : t.substring(0, cut + 1);
        }
        Matcher m = SENT.matcher(t);
        boolean first = true;
        while (m.find()) {
            String s = m.group().strip();
            if (first) { s = DATELINE.matcher(s).replaceFirst(""); first = false; }
            if (s.isEmpty() || !Character.isUpperCase(s.codePointAt(0)) && !Character.isDigit(s.charAt(0)) && s.charAt(0) != '"' && s.charAt(0) != '“') continue;
            out.add(s);
        }
        return out;
    }

    public static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : norm(s).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            out.add(w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    private static String norm(String s) { return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT); }

    private static double jaccard(Set<String> a, Set<String> b) {
        int i = 0;
        for (String x : a) if (b.contains(x)) i++;
        int u = a.size() + b.size() - i;
        return u == 0 ? 0 : (double) i / u;
    }
}
