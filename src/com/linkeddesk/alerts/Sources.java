package com.linkeddesk.alerts;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Outlets you mute or weight (ALR-F03), in sources.txt:
 * <pre>
 * Daily Tribune = mute
 * Reuters = 2        (counts double when ranking stories)
 * Yahoo = 0.5        (counts half)
 * </pre>
 * Muted outlets disappear from every list but stay in the archive (unmute and they're back). Weights change how
 * "covered" a story counts in the Most covered sort and the briefing. The name matches outlets that contain it as
 * whole words ("Yahoo" also covers "Yahoo Finance"); the longest name wins.
 */
public final class Sources {
    public record Rule(String name, boolean mute, double weight) {}

    private final Path file;
    private final List<Rule> rules = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final Map<String, Rule> cache = new ConcurrentHashMap<>();
    private long stamp = Long.MIN_VALUE;

    public static final String HEADER = """
            # Outlets you mute or weight. One per line:  outlet name = mute   or   outlet name = weight (0.25-5)
            # Muted outlets are hidden from lists but kept in the archive. Weight 2 counts an outlet double when ranking.
            """;

    public Sources(Path home) { this.file = home.resolve("sources.txt"); }

    /** Reads sources.txt again if it changed. */
    public synchronized Sources load() {
        long st;
        try { st = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file) : 0; } catch (IOException e) { st = 0; }
        if (st == stamp) return this;
        stamp = st;
        rules.clear();
        cache.clear();
        try {
            {
                for (String raw : com.linkeddesk.app.SafeFiles.readLines(file)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) continue;
                    String name = line.substring(0, eq).strip(), v = line.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
                    if (name.isEmpty()) continue;
                    if (v.startsWith("mute") || v.equals("hide") || v.equals("off")) rules.add(new Rule(name, true, 1));
                    else try { rules.add(new Rule(name, false, Math.max(0.25, Math.min(5, Double.parseDouble(v.replace("weight", "").strip()))))); }
                    catch (NumberFormatException ignored) { }
                }
            }
            unreadable = false;
        } catch (IOException e) { unreadable = true; stamp = Long.MIN_VALUE; }
        rules.sort(Comparator.comparingInt((Rule r) -> r.name().length()).reversed());
        patterns.clear();
        for (Rule r : rules) patterns.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(r.name().toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}])"));
        return this;
    }

    private static final Rule NONE = new Rule("", false, 1);

    private Rule rule(String outlet) {
        if (outlet == null || rules.isEmpty()) return NONE;
        return cache.computeIfAbsent(outlet, o -> {
            String s = o.toLowerCase(Locale.ROOT);
            synchronized (this) {
                for (int i = 0; i < rules.size(); i++) if (patterns.get(i).matcher(s).find()) return rules.get(i);
            }
            return NONE;
        });
    }

    public boolean muted(String outlet) { return rule(outlet).mute(); }
    public double weight(String outlet) { return rule(outlet).weight(); }
    public synchronized List<Rule> rules() { return List.copyOf(rules); }
    public synchronized boolean any() { return !rules.isEmpty(); }

    /** The articles from outlets you haven't muted. */
    public List<Article> visible(List<Article> articles) {
        if (!any()) return articles;
        List<Article> out = new ArrayList<>(articles.size());
        for (Article a : articles) if (!muted(a.source)) out.add(a);
        return out;
    }

    /** How "covered" a story counts: its outlets, each counted by its weight. */
    public double coverage(StoryCluster c) {
        if (!any()) return c.independentCount();
        // Each outlet by its weight; a group of outlets carrying the same wire copy counts once (its highest weight),
        // and opinion pieces don't count (ANL-F12, ANL-F13).
        java.util.Map<String, Double> units = new java.util.HashMap<>();
        java.util.Map<String, String> group = new java.util.HashMap<>();
        for (var cp : c.copies) { String k = "copy:" + System.identityHashCode(cp); for (Article a : cp.articles()) group.putIfAbsent(a.source, k); }
        for (Article a : c.articles) {
            if (com.linkeddesk.analysis.Coverage.opinion(a)) continue;
            units.merge(group.getOrDefault(a.source, a.source), weight(a.source), Math::max);
        }
        if (units.isEmpty()) for (String o : c.sources()) units.put(o, weight(o));
        double s = 0;
        for (double w : units.values()) s += w;
        return s;
    }

    /** Sets or clears your rule for one outlet: mute, a weight, or (weight 1, not muted) no rule. */
    private boolean unreadable;

    public synchronized void set(String outlet, boolean mute, double weight) throws IOException {
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, file);
        Map<String, Rule> byName = new LinkedHashMap<>();
        for (Rule r : rules) byName.put(r.name(), r);
        if (!mute && Math.abs(weight - 1) < 1e-9) byName.remove(outlet);
        else byName.put(outlet, new Rule(outlet, mute, mute ? 1 : weight));
        StringBuilder sb = new StringBuilder(HEADER);
        for (Rule r : byName.values()) sb.append(r.name().replace("=", "-")).append(" = ").append(r.mute() ? "mute" : fmt(r.weight())).append('\n');
        com.linkeddesk.app.SafeFiles.write(file, sb.toString());
        stamp = Long.MIN_VALUE;
        load();
    }

    /** Outlets in these articles, most articles first, with counts. */
    public static Map<String, Integer> outlets(List<Article> articles) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Article a : articles) m.merge(a.source, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.format(Locale.ROOT, "%.0f", d) : String.format(Locale.ROOT, "%.2f", d); }

    public static Set<String> names(List<Rule> rules) {
        Set<String> s = new LinkedHashSet<>();
        for (Rule r : rules) s.add(r.name());
        return s;
    }
}
