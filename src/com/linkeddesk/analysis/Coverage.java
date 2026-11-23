package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much independent reporting a story has (ANL-F12, ANL-F13). Outlets that run the same wire-service copy
 * (AP, Reuters, AFP…) or the same text count once, and opinion or analysis pieces aren't counted as reporting.
 */
public final class Coverage {
    private Coverage() {}

    /** A set of articles from different outlets that are one report: the same wire copy or the same text. */
    public record Copies(String agency, List<Article> articles) {
        public int outlets() { Set<String> s = new HashSet<>(); for (Article a : articles) s.add(a.source); return s.size(); }
        /** "AP" or "the same text". */
        public String label() { return agency.isEmpty() ? "the same text" : agency; }
    }

    // ------------------------------------------------------------------ wire copies

    private static final Pattern WIRE = Pattern.compile("\\((AP|Reuters|AFP|Bloomberg|dpa|UPI|PA Media|ANI|IANS|PTI|Xinhua)\\)|—\\s*(The Associated Press|Reuters|AFP)\\s*$"
            + "|^\\s*(?:[A-Z][\\w .'-]{1,40},\\s*)?(?:[A-Z][a-z]{2,8}\\.? \\d{1,2},?\\s*)?\\((AP|Reuters|AFP)\\)");
    private static final Map<String, String> AGENCY_OUTLETS = Map.of(
            "ap news", "AP", "associated press", "AP", "the associated press", "AP", "reuters", "Reuters", "afp", "AFP",
            "agence france-presse", "AFP", "bloomberg", "Bloomberg");

    /** The wire service an article says it comes from ("AP", "Reuters"…), or "". */
    public static String agency(Article a) {
        String own = AGENCY_OUTLETS.get(a.source.toLowerCase(Locale.ROOT).strip());
        if (own != null) return own;
        for (String t : new String[]{a.summary, a.title}) {
            Matcher m = WIRE.matcher(t);
            if (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                return switch (g) { case "The Associated Press" -> "AP"; case "PA Media" -> "PA"; default -> g; };
            }
        }
        return "";
    }

    static Set<String> shingles(String text) {
        String[] w = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+");
        Set<String> s = new HashSet<>();
        for (int i = 0; i + 2 < w.length; i++) s.add(w[i] + " " + w[i + 1] + " " + w[i + 2]);
        return s;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int in = 0;
        for (String x : a) if (b.contains(x)) in++;
        return (double) in / (a.size() + b.size() - in);
    }

    /** The groups of articles in a story that are one report carried by several outlets. */
    public static List<Copies> copies(List<Article> articles) {
        int n = articles.size();
        if (n < 2) return List.of();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        String[] ag = new String[n];
        List<Set<String>> sh = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { ag[i] = agency(articles.get(i)); sh.add(shingles(articles.get(i).summary)); }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                Article a = articles.get(i), b = articles.get(j);
                if (a.source.equals(b.source)) continue;
                boolean same = !ag[i].isEmpty() && ag[i].equals(ag[j]);
                if (!same && sh.get(i).size() >= 8 && sh.get(j).size() >= 8 && jaccard(sh.get(i), sh.get(j)) >= 0.6) same = true;
                if (same) union(parent, i, j);
            }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        List<Copies> out = new ArrayList<>();
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            List<Article> arts = new ArrayList<>();
            String agency = "";
            for (int i : g) { arts.add(articles.get(i)); if (agency.isEmpty()) agency = ag[i]; }
            Copies c = new Copies(agency, arts);
            if (c.outlets() >= 2) out.add(c);
        }
        return out;
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    // ------------------------------------------------------------------ opinion

    private static final Pattern OPINION_PATH = Pattern.compile("(?i)/(opinion|opinions|commentisfree|comment|editorial|editorials|columns?|columnists?|"
            + "op-?ed|analysis|perspective|perspectives|viewpoint|viewpoints|letters|commentary|ideas)(/|-|$)");
    private static final Pattern OPINION_TITLE = Pattern.compile("(?i)^\\s*(opinion|analysis|editorial|op-ed|commentary|column|letters?|"
            + "view|the big read|explainer: opinion|our view|guest column|perspective)\\s*[:|\\-–—]");
    private static final Pattern OPINION_SOURCE = Pattern.compile("(?i)\\b(opinion|editorial board|commentary)\\b");

    /** True for opinion, analysis and commentary pieces (from the link, the headline or the feed's name). */
    public static boolean opinion(Article a) {
        String path = a.url.replaceFirst("^https?://[^/]+", "").replaceFirst("[?#].*$", "");
        return OPINION_PATH.matcher(path).find() || OPINION_TITLE.matcher(a.title).find() || OPINION_SOURCE.matcher(a.source).find();
    }

    // ------------------------------------------------------------------ the count

    /** Outlets with their own reporting: copies of one report count once, opinion pieces not at all. */
    public static int independent(StoryCluster c) {
        Map<String, String> group = new HashMap<>();          // outlet -> its copy group's key
        for (Copies cp : c.copies) {
            String key = "copy:" + System.identityHashCode(cp);
            for (Article a : cp.articles()) group.putIfAbsent(a.source, key);
        }
        Set<String> units = new HashSet<>();
        boolean anyReporting = false;
        for (Article a : c.articles) {
            if (opinion(a)) continue;
            anyReporting = true;
            units.add(group.getOrDefault(a.source, a.source));
        }
        return anyReporting ? units.size() : c.sourceCount();
    }
}
