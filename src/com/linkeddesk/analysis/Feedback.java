package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your verdicts on links and groupings, kept in link-feedback.tsv:
 * <ul>
 *   <li>RIGHT: the link between two stories is real. It is always shown.</li>
 *   <li>WRONG: the link is wrong. It is hidden, and other links that rest on the same two words are weakened.</li>
 *   <li>SPLIT: an article was grouped into a story it doesn't belong to. It is kept out of that story.</li>
 * </ul>
 * Stories are remembered by their article ids, so a verdict still applies after new articles join the story
 * or the story is regrouped on the next download.
 */
public final class Feedback {
    public enum Kind { RIGHT, WRONG, SPLIT }

    /**
     * One verdict. For links, {@code a} and {@code b} are article ids of the two stories. For SPLIT, {@code a}
     * holds the single article taken out and {@code b} the other articles of the story it was taken out of.
     */
    public record Entry(Kind kind, long time, Set<String> a, Set<String> b, List<String> terms, String headlineA, String headlineB) {
        public String key() { return kind + "|" + String.join(",", a) + "|" + String.join(",", b); }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<Entry>> byArticle = new HashMap<>();

    public static final Feedback NONE = new Feedback();

    public List<Entry> entries() { return List.copyOf(entries); }
    public boolean isEmpty() { return entries.isEmpty(); }

    public long count(Kind k) { return entries.stream().filter(e -> e.kind() == k).count(); }

    public void add(Entry e) {
        entries.removeIf(x -> x.kind() != Kind.SPLIT && e.kind() != Kind.SPLIT && samePair(x, e));
        entries.add(e);
        index();
    }

    public void remove(Entry e) {
        entries.remove(e);
        index();
    }

    private static boolean samePair(Entry x, Entry y) {
        return (overlaps(x.a(), y.a()) && overlaps(x.b(), y.b())) || (overlaps(x.a(), y.b()) && overlaps(x.b(), y.a()));
    }

    private static boolean overlaps(Set<String> p, Set<String> q) {
        for (String s : p) if (q.contains(s)) return true;
        return false;
    }

    private void index() {
        byArticle.clear();
        for (Entry e : entries) for (String id : e.a()) byArticle.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
    }

    /** The verdicts that mention this article on their first side. */
    List<Entry> about(String articleId) { return byArticle.getOrDefault(articleId, List.of()); }

    /** Article ids of a story, at most 30 (enough to recognise it later). */
    public static Set<String> ids(StoryCluster c) {
        Set<String> s = new LinkedHashSet<>();
        for (Article a : c.articles) { s.add(a.id); if (s.size() >= 30) break; }
        return s;
    }

    /** Your latest link verdict on these two stories, or null. */
    public Entry find(StoryCluster x, StoryCluster y) {
        Set<String> ix = new HashSet<>(), iy = new HashSet<>();
        for (Article a : x.articles) ix.add(a.id);
        for (Article a : y.articles) iy.add(a.id);
        Entry found = null;
        for (Entry e : entries) {
            if (e.kind() == Kind.SPLIT) continue;
            if ((overlaps(e.a(), ix) && overlaps(e.b(), iy)) || (overlaps(e.a(), iy) && overlaps(e.b(), ix))) found = e;
        }
        return found;
    }

    /** The verdict for the link between these two stories, if you gave one. */
    public Kind verdict(StoryCluster x, StoryCluster y) {
        Set<String> ix = new HashSet<>(), iy = new HashSet<>();
        for (Article a : x.articles) ix.add(a.id);
        for (Article a : y.articles) iy.add(a.id);
        Kind k = null;
        for (Entry e : entries) {
            if (e.kind() == Kind.SPLIT) continue;
            if ((overlaps(e.a(), ix) && overlaps(e.b(), iy)) || (overlaps(e.a(), iy) && overlaps(e.b(), ix))) k = e.kind();
        }
        return k;
    }

    // ------------------------------------------------------------------ file

    public static Feedback load(Path file) throws IOException {
        Feedback f = new Feedback();
        if (!Files.exists(file)) return f;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 4) continue;
            try {
                Kind k = Kind.valueOf(p[0]);
                f.entries.add(new Entry(k, Long.parseLong(p[1]), set(p[2]), set(p[3]),
                        p.length > 4 && !p[4].isEmpty() ? List.of(p[4].split("\\|")) : List.of(),
                        p.length > 5 ? p[5] : "", p.length > 6 ? p[6] : ""));
            } catch (RuntimeException ignored) {
                // a damaged line is skipped
            }
        }
        f.index();
        return f;
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("# Your verdicts on links (RIGHT/WRONG) and on articles taken out of stories (SPLIT).\n"
                + "# kind\ttime\tarticle ids (story 1)\tarticle ids (story 2)\tshared words\theadline 1\theadline 2\n");
        for (Entry e : entries) {
            sb.append(e.kind()).append('\t').append(e.time()).append('\t').append(String.join(",", e.a())).append('\t')
              .append(String.join(",", e.b())).append('\t').append(String.join("|", e.terms())).append('\t')
              .append(clean(e.headlineA())).append('\t').append(clean(e.headlineB())).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    private static Set<String> set(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.strip());
        return out;
    }
}
