package com.linkeddesk.ai;

import com.linkeddesk.analysis.Language;
import com.linkeddesk.model.Article;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * English versions of non-English headlines and summaries (ANL-F11), in translations.tsv:
 * id, language, English headline, English summary. Made by Claude (cached, within the monthly budget); used for
 * grouping and linking, while the story page also shows the original.
 */
public final class Translations {
    public record Entry(String lang, String title, String summary) {}

    private final Path file;
    private final Map<String, Entry> map = new HashMap<>();
    private long stamp = Long.MIN_VALUE;

    public Translations(Path home) { this.file = home.resolve("translations.tsv"); }

    public synchronized Translations load() {
        long st;
        try { st = Files.exists(file) ? Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis() : 0; } catch (IOException e) { st = 0; }
        if (st == stamp) return this;
        stamp = st;
        map.clear();
        try {
            if (Files.exists(file)) for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length >= 4) map.put(p[0], new Entry(p[1], unesc(p[2]), unesc(p[3])));   // an empty headline: tried, nothing to use
            }
        } catch (IOException ignored) { }
        return this;
    }

    public synchronized boolean isEmpty() { return map.isEmpty(); }
    /** Translations with text (not counting items that were tried and skipped). */
    public synchronized int translated() { int n = 0; for (Entry e : map.values()) if (!e.title().isEmpty()) n++; return n; }
    public synchronized int size() { return map.size(); }
    public synchronized Entry get(String id) { return map.get(id); }
    public synchronized boolean has(String id) { return map.containsKey(id); }
    public synchronized List<String> ids() { return new ArrayList<>(map.keySet()); }

    /** The article in English if a translation is saved (with its language and original headline kept), else itself. */
    public Article apply(Article a) {
        Entry e = get(a.id);
        if (e == null || e.title().isEmpty()) return a;
        Article t = a.withText(e.title(), e.summary().isEmpty() ? a.summary : e.summary());
        t.lang = e.lang();
        t.originalTitle = a.title;
        return t;
    }

    public synchronized void add(Map<String, Entry> more) throws IOException {
        if (more.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (var e : more.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().lang()).append('\t').append(esc(e.getValue().title())).append('\t').append(esc(e.getValue().summary())).append('\n');
            map.put(e.getKey(), e.getValue());
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        stamp = Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis();
    }

    /** How many translations were made today (for the daily limit). */
    public synchronized int madeOn(java.time.LocalDate day) throws IOException {
        Path log = file.resolveSibling("translations-log.tsv");
        if (!Files.exists(log)) return 0;
        int n = 0;
        for (String l : Files.readAllLines(log, StandardCharsets.UTF_8)) if (l.startsWith(day.toString() + "\t")) n += Integer.parseInt(l.split("\t")[1].trim());
        return n;
    }

    synchronized void logMade(java.time.LocalDate day, int n) throws IOException {
        Files.writeString(file.resolveSibling("translations-log.tsv"), day + "\t" + n + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ asking Claude

    public static final int BATCH = 25;
    public static final int MAX_SUMMARY = 350;

    /** The request for one batch: numbered items, one line each back. */
    public static String prompt(List<Article> batch) {
        StringBuilder sb = new StringBuilder("""
                Translate these news headlines and summaries into English for a news reader. Keep names of people, places and \
                organizations in their usual English spelling. Translate faithfully; don't add or explain anything.
                Reply with exactly one line per item, in this form and nothing else:
                N | English headline | English summary
                (leave the summary empty if there is none)

                """);
        int i = 0;
        for (Article a : batch) {
            String sum = a.summary.length() > MAX_SUMMARY ? a.summary.substring(0, MAX_SUMMARY) + "…" : a.summary;
            sb.append(++i).append(" | ").append(Language.name(Language.detect(a.title + " " + a.summary))).append(" | ")
              .append(a.title.replace('|', '/').replace('\n', ' ')).append(" | ").append(sum.replace('|', '/').replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s*[|.)]\\s*(.*?)\\s*(?:\\|\\s*(.*?))?\\s*$");

    /** Reads Claude's reply; items it skipped or garbled are left out (they're tried again next time). */
    public static Map<String, Entry> parse(String reply, List<Article> batch) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String l : reply.split("\\R")) {
            Matcher m = LINE.matcher(l);
            if (!m.matches()) continue;
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > batch.size()) continue;
            String title = m.group(2).strip(), sum = m.group(3) == null ? "" : m.group(3).strip();
            if (title.isEmpty() || title.length() > 400) continue;
            Article a = batch.get(n - 1);
            out.put(a.id, new Entry(Language.detect(a.title + " " + a.summary), title, sum));
        }
        return out;
    }

    /** Translates up to {@code limit} of these articles (those not yet translated), a batch per request. Returns how many. */
    public int translate(List<Article> candidates, int limit, ClaudeService claude) throws Exception {
        List<Article> todo = new ArrayList<>();
        for (Article a : candidates) {
            if (todo.size() >= limit) break;
            if (has(a.id) || "en".equals(Language.detect(a.title + " " + a.summary))) continue;
            todo.add(a);
        }
        int done = 0;
        for (int i = 0; i < todo.size(); i += BATCH) {
            List<Article> batch = todo.subList(i, Math.min(todo.size(), i + BATCH));
            String reply = claude.ask("translate", prompt(batch), 90 * batch.size() + 100);
            Map<String, Entry> got = parse(reply, batch);
            done += got.size();
            // Items Claude left out are marked as tried, so they aren't paid for again every day.
            Map<String, Entry> all = new LinkedHashMap<>(got);
            for (Article a : batch) all.putIfAbsent(a.id, new Entry(Language.detect(a.title + " " + a.summary), "", ""));
            add(all);
        }
        if (done > 0) logMade(java.time.LocalDate.now(), done);
        return done;
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", ""); }

    private static String unesc(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { char n = s.charAt(++i); sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n); }
            else sb.append(c);
        }
        return sb.toString();
    }
}
