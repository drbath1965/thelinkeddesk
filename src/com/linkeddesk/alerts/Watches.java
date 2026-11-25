package com.linkeddesk.alerts;

import com.linkeddesk.analysis.Lexicon;
import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.analysis.Text;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your watchlist (ALR-F01): subjects to follow across every desk, in watchlist.txt:
 * <pre>Name | search words | notify (yes/no)</pre>
 * Search words work like Search ("strait of hormuz" OR tankers, -word, source:…). Names are matched through the
 * alias list too: a watch on "Fed" also finds "Federal Reserve", and a watch on "US" finds "United States".
 * When you last looked at each watch is kept in watch-seen.tsv, so new matches stand out.
 */
public final class Watches {
    public record Watch(String name, String words, boolean alert) {}

    private final Path file, seenFile;
    private final List<Watch> watches = new ArrayList<>();
    private final Map<String, Long> seen = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private Lexicon lexicon = Lexicon.EMPTY;
    private final Map<String, SearchQuery> compiled = new HashMap<>();
    /** Word beginnings of every alias form the watches use: an article without them needs no alias names added. */
    private List<List<String>> aliasHints = List.of();
    /** Article id -> watches it matches (articles don't change; cleared when the watchlist or aliases change). */
    private final Map<String, List<Watch>> memo = new HashMap<>();

    public static final String HEADER = """
            # Your watchlist: subjects to follow across all desks. One per line:
            #   Name | search words | notify (yes/no)
            # Search words work like Search: "exact phrase", OR, -leave out, source:…, desk:…
            # Names also match their aliases (aliases.txt): Fed = Federal Reserve, US = United States.
            """;

    public Watches(Path home) {
        this.file = home.resolve("watchlist.txt");
        this.seenFile = home.resolve("watch-seen.tsv");
    }

    private long stamp = Long.MIN_VALUE;

    private long stampNow() {
        long s = 17;
        for (Path f : new Path[]{file, seenFile}) {
            try { s = s * 31 + (Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() * 7 + Files.size(f) : 1); } catch (IOException e) { s = s * 31; }
        }
        return s;
    }

    /** Reads the watchlist (only if the files or the alias list changed since the last read). */
    public synchronized Watches load(Lexicon lex) {
        Lexicon lx = lex == null ? Lexicon.EMPTY : lex;
        long st = stampNow();
        if (st == stamp && lx == lexicon) return this;
        stamp = st;
        this.lexicon = lx;
        memo.clear();
        aliasHints = List.of();
        watches.clear();
        problems.clear();
        compiled.clear();
        try {
            {
                int n = 0;
                for (String raw : com.linkeddesk.app.SafeFiles.readLines(file)) {
                    n++;
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length < 2 || p[0].isBlank() || p[1].isBlank()) { problems.add("watchlist.txt line " + n + ": needs Name | search words"); continue; }
                    boolean notify = p.length < 3 || !p[2].strip().toLowerCase(Locale.ROOT).startsWith("n");
                    watches.add(new Watch(p[0].strip(), p[1].strip(), notify));
                }
            }
            seen.clear();
            {
                for (String l : com.linkeddesk.app.SafeFiles.readLines(seenFile)) {
                    String[] p = l.split("\t");
                    if (p.length == 2) try { seen.put(p[0], Long.parseLong(p[1])); } catch (NumberFormatException ignored) { }
                }
            }
            unreadable = false;
        } catch (IOException e) { unreadable = true; stamp = Long.MIN_VALUE; }
        return this;
    }

    public synchronized List<Watch> all() { return List.copyOf(watches); }
    public synchronized List<String> problems() { return List.copyOf(problems); }
    public synchronized boolean isEmpty() { return watches.isEmpty(); }

    private boolean unreadable;

    public synchronized void save(List<Watch> list) throws IOException {
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, file);
        StringBuilder sb = new StringBuilder(HEADER);
        for (Watch w : list) sb.append(w.name().replace("|", "/")).append(" | ").append(w.words().replace("|", " ")).append(" | ").append(w.alert() ? "yes" : "no").append('\n');
        com.linkeddesk.app.SafeFiles.write(file, sb.toString());
        stamp = Long.MIN_VALUE;
        load(lexicon);
    }

    /** When you last opened this watch (0 = never). */
    public synchronized long lastSeen(String name) { return seen.getOrDefault(name, 0L); }

    public synchronized void markSeen(String name, long time) throws IOException {
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, seenFile);
        seen.put(name, time);
        StringBuilder sb = new StringBuilder();
        for (var e : seen.entrySet()) sb.append(e.getKey().replace('\t', ' ')).append('\t').append(e.getValue()).append('\n');
        com.linkeddesk.app.SafeFiles.write(seenFile, sb.toString());
        stamp = stampNow();
    }

    // ------------------------------------------------------------------ matching

    private static final Pattern TERM = Pattern.compile("(-?)(\"[^\"]*\"|\\S+)");

    /** The watch's words with names replaced by their full alias name ("Fed" -> "federal reserve"). */
    private synchronized List<List<String>> hints() {
        if (!aliasHints.isEmpty() || watches.isEmpty()) return aliasHints;
        List<List<String>> h = new ArrayList<>();
        Set<String> canon = new LinkedHashSet<>();
        for (Watch w : watches) {
            Matcher m = TERM.matcher(w.words());
            while (m.find()) {
                String tok = m.group(2);
                if (tok.equals("OR") || tok.matches("[A-Za-z]+:.*")) continue;
                String c = lexicon.canonical(Lexicon.key(tok.replace("\"", "")));
                if (c != null) canon.add(c);
            }
        }
        for (String c : canon)
            for (String k : lexicon.keysFor(c)) {
                List<String> parts = new ArrayList<>();
                for (String word : k.split(" ")) parts.add(word.substring(0, Math.min(4, word.length())));
                h.add(parts);
            }
        if (h.isEmpty()) h.add(List.of("\u0000none"));
        aliasHints = h;
        return h;
    }

    /** True if the article may name one of the aliases the watches use (cheap check before {@link #withNames}). */
    private boolean mayNeedNames(Article a) {
        String t = (a.title + " " + a.summary).toLowerCase(Locale.ROOT).replace(".", "").replace("'", "").replace("’", "");
        for (List<String> form : hints()) {
            boolean all = true;
            for (String p : form) if (!t.contains(p)) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    public String expanded(Watch w) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TERM.matcher(w.words());
        while (m.find()) {
            String tok = m.group(2);
            String out = m.group();
            boolean field = tok.matches("[A-Za-z]+:.*");
            if (!tok.equals("OR") && !field) {
                String c = lexicon.canonical(Lexicon.key(tok.replace("\"", "")));
                if (c != null && lexicon.display(c) != null) out = m.group(1) + "\"" + lexicon.display(c).toLowerCase(Locale.ROOT) + "\"";
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(out);
        }
        return sb.toString();
    }

    private synchronized SearchQuery query(Watch w) {
        return compiled.computeIfAbsent(w.name(), k -> new SearchQuery(expanded(w)));
    }

    /**
     * The article as watches see it: its text plus the full names of any aliases in it, so "Fed" in a headline
     * matches a watch on "Federal Reserve".
     */
    public Article withNames(Article a) {
        StringBuilder extra = new StringBuilder();
        Set<String> added = new LinkedHashSet<>();
        for (String text : new String[]{a.title, a.summary}) {
            for (Text.Token t : Text.tokens(text, lexicon)) {
                String d = lexicon.display(t.term());
                if (d != null && added.add(d)) extra.append(" · ").append(d);
            }
        }
        if (extra.length() == 0) return a;
        Article x = new Article(a.id, a.url, a.title, a.summary + extra, a.source, a.feedCategory, a.published, a.fetched);
        x.setCategory(a.category());
        return x;
    }

    /** The watches an article matches. */
    public List<Watch> matching(Article a) {
        List<Watch> ws;
        synchronized (this) {
            if (watches.isEmpty()) return List.of();
            List<Watch> m = memo.get(a.id);
            if (m != null) return m;
            ws = List.copyOf(watches);
        }
        Article x = mayNeedNames(a) ? withNames(a) : a;
        String hay = SearchQuery.folded(x);
        List<Watch> out = new ArrayList<>(1);
        for (Watch w : ws) {
            SearchQuery q = query(w);
            if ((!q.hasWords() || q.matchesFolded(hay)) && !q.isEmpty() && q.fieldsMatch(x, null, null, null)) out.add(w);
        }
        List<Watch> r = out.isEmpty() ? List.of() : List.copyOf(out);
        synchronized (this) {
            if (memo.size() > 500_000) memo.clear();
            memo.put(a.id, r);
        }
        return r;
    }

    /** For each story, the watches it matches (any of its articles). */
    public Map<StoryCluster, List<Watch>> stories(List<StoryCluster> stories) {
        Map<StoryCluster, List<Watch>> out = new LinkedHashMap<>();
        if (watches.isEmpty()) return out;
        for (StoryCluster c : stories) {
            Set<Watch> hit = new LinkedHashSet<>();
            for (Article a : c.articles) hit.addAll(matching(a));
            if (!hit.isEmpty()) out.put(c, new ArrayList<>(hit));
        }
        return out;
    }

    /** New articles per watch (only watches with notify on): what a notification after a download reports. */
    public Map<Watch, List<Article>> newHits(List<Article> added) {
        Map<Watch, List<Article>> out = new LinkedHashMap<>();
        for (Article a : added)
            for (Watch w : matching(a)) if (w.alert()) out.computeIfAbsent(w, k -> new ArrayList<>()).add(a);
        return out;
    }
}
