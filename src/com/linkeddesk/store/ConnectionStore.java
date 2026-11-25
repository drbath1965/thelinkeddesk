package com.linkeddesk.store;

import com.linkeddesk.ai.Connections;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Connections Claude found (AI-F09), in connections.tsv next to feeds.txt: what it suggested for each story, and
 * which you accepted or rejected. Stories are recognised by their article ids (up to 30), so a connection still
 * finds its stories after the next download regroups the news. Lines:
 * status, time, kind, confidence, ids A, ids B, headline A, headline B, link B, reason.
 */
public final class ConnectionStore {
    public enum Status { SUGGESTED, ACCEPTED, REJECTED, CHECKED }

    public record Entry(Status status, long time, Connections.Kind kind, Connections.Confidence confidence, Set<String> a, Set<String> b,
                        String headlineA, String headlineB, String urlB, String reason) {
        public String pairKey() { return String.join(",", new TreeSet<>(a)) + "|" + String.join(",", new TreeSet<>(b)); }
        Entry with(Status s, long t) { return new Entry(s, t, kind, confidence, a, b, headlineA, headlineB, urlB, reason); }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private long loadedStamp = Long.MIN_VALUE;

    public ConnectionStore(Path file) { this.file = file; }

    public synchronized List<Entry> entries() { load(); return List.copyOf(entries); }

    public static Set<String> ids(StoryCluster c) {
        Set<String> s = new LinkedHashSet<>();
        for (Article a : c.articles) { s.add(a.id); if (s.size() >= 30) break; }
        return s;
    }

    private static Set<String> all(StoryCluster c) { Set<String> s = new HashSet<>(); for (Article a : c.articles) s.add(a.id); return s; }

    private static boolean overlaps(Set<String> p, Set<String> q) { for (String x : p) if (q.contains(x)) return true; return false; }

    private static boolean pair(Entry e, Set<String> x, Set<String> y) {
        return (overlaps(e.a(), x) && overlaps(e.b(), y)) || (overlaps(e.a(), y) && overlaps(e.b(), x));
    }

    /** Your decision on this pair (ACCEPTED or REJECTED), or null. The latest wins. */
    public synchronized Status decision(StoryCluster x, StoryCluster y) {
        load();
        Set<String> ix = all(x), iy = all(y);
        Status s = null;
        for (Entry e : entries) if ((e.status() == Status.ACCEPTED || e.status() == Status.REJECTED) && pair(e, ix, iy)) s = e.status();
        return s;
    }

    /** The accepted connection between these stories, or null. */
    public synchronized Entry accepted(StoryCluster x, StoryCluster y) {
        load();
        Set<String> ix = all(x), iy = all(y);
        Entry found = null;
        for (Entry e : entries) if (pair(e, ix, iy)) found = e.status() == Status.ACCEPTED ? e : e.status() == Status.REJECTED ? null : found;
        return found;
    }

    public synchronized List<Entry> accepted() {
        load();
        Map<String, Entry> last = new LinkedHashMap<>();
        for (Entry e : entries) if (e.status() == Status.ACCEPTED || e.status() == Status.REJECTED) last.put(e.pairKey(), e);
        List<Entry> out = new ArrayList<>();
        for (Entry e : last.values()) if (e.status() == Status.ACCEPTED) out.add(e);
        return out;
    }

    /** Claude's open suggestions for this story (not yet accepted or rejected), from its latest check. */
    public synchronized List<Entry> suggestions(StoryCluster target) {
        load();
        Set<String> mine = all(target);
        long latest = -1;
        for (Entry e : entries) if ((e.status() == Status.SUGGESTED || e.status() == Status.CHECKED) && overlaps(e.a(), mine)) latest = Math.max(latest, e.time());
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.status() != Status.SUGGESTED || e.time() != latest || !overlaps(e.a(), mine)) continue;
            boolean decided = false;
            for (Entry d : entries)
                if ((d.status() == Status.ACCEPTED || d.status() == Status.REJECTED) && d.time() >= e.time()
                        && (overlaps(d.a(), e.a()) && overlaps(d.b(), e.b()) || overlaps(d.a(), e.b()) && overlaps(d.b(), e.a()))) decided = true;
            if (!decided) out.add(e);
        }
        return out;
    }

    /** When Claude last looked for connections of this story (0 = never). */
    public synchronized long checked(StoryCluster target) {
        load();
        Set<String> mine = all(target);
        long t = 0;
        for (Entry e : entries) if ((e.status() == Status.CHECKED || e.status() == Status.SUGGESTED) && overlaps(e.a(), mine)) t = Math.max(t, e.time());
        return t;
    }

    /** Records a check: one CHECKED line (so "nothing found" is remembered too) and a SUGGESTED line per connection. */
    public synchronized void record(Connections.Result r, long time) throws IOException {
        load();
        StoryCluster t = r.target();
        entries.add(new Entry(Status.CHECKED, time, null, null, ids(t), Set.of(), t.headline, "", "", ""));
        for (Connections.Found f : r.found()) {
            StoryCluster o = f.candidate().story();
            entries.add(new Entry(Status.SUGGESTED, time, f.kind(), f.confidence(), ids(t), ids(o), t.headline, o.headline, lead(o), f.reason()));
        }
        save();
    }

    public synchronized Entry decide(Entry e, boolean accept) throws IOException {
        load();
        Entry d = e.with(accept ? Status.ACCEPTED : Status.REJECTED, Math.max(System.currentTimeMillis(), e.time() + 1));
        entries.add(d);
        save();
        return d;
    }

    static String lead(StoryCluster c) {
        Article best = null;
        for (Article a : c.articles) if (best == null || a.published < best.published) best = a;
        return best == null ? "" : best.url;
    }

    // ------------------------------------------------------------------ file

    private void load() {
        long st;
        try { st = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file) : -1; } catch (IOException e) { st = -2; }
        if (st == loadedStamp) return;
        entries.clear();
        loadedStamp = st;
        if (st < 0) return;
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] p = l.split("\t", -1);
                if (p.length < 10) continue;
                try {
                    Status s = Status.valueOf(p[0]);
                    Connections.Kind k = p[2].isEmpty() ? null : Connections.Kind.valueOf(p[2]);
                    Connections.Confidence c = p[3].isEmpty() ? null : Connections.Confidence.valueOf(p[3]);
                    entries.add(new Entry(s, Long.parseLong(p[1]), k, c, set(p[4]), set(p[5]), p[6], p[7], p[8], p[9]));
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        StringBuilder sb = new StringBuilder("# Connections Claude found (The Linked Desk). status\\ttime\\tkind\\tconfidence\\tids A\\tids B\\theadline A\\theadline B\\tlink B\\treason\n");
        for (Entry e : entries)
            sb.append(e.status()).append('\t').append(e.time()).append('\t').append(e.kind() == null ? "" : e.kind().name()).append('\t')
              .append(e.confidence() == null ? "" : e.confidence().name()).append('\t').append(String.join(",", e.a())).append('\t')
              .append(String.join(",", e.b())).append('\t').append(clean(e.headlineA())).append('\t').append(clean(e.headlineB())).append('\t')
              .append(clean(e.urlB())).append('\t').append(clean(e.reason())).append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        loadedStamp = Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    private static Set<String> set(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.strip());
        return out;
    }
}
