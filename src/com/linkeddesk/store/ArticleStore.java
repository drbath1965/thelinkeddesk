package com.linkeddesk.store;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps every article ever downloaded, in plain tab-separated files (one per month) under data/.
 * Nothing is ever deleted or capped.
 *
 * Scale: at start-up only the most recent months are loaded in full; older months contribute only to a
 * compact index (ids and headline keys) used for de-duplication. Older months load on demand
 * ({@link #since}, {@link #all}) or are streamed for searches ({@link #scan}) without being kept in memory.
 *
 * Safety: each batch is written with one write and flushed to disk. A line cut off by a crash or power
 * loss is skipped and counted on load, and the file is repaired (a newline added) before the next write.
 * A backup copy of the current month is kept in data/backup and refreshed weekly.
 */
public final class ArticleStore {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    static final int RECENT_MONTHS = 2;
    static final long BACKUP_EVERY_MS = 7L * 24 * 3600_000;

    private final Path dir;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Map<String, Article> byId = new LinkedHashMap<>();
    private final Set<String> ids = new HashSet<>();
    private final Map<Long, String> titleKeys = new HashMap<>();   // headline key -> stored id
    private final Map<Path, Long> offsets = new HashMap<>();     // bytes already read per file
    private final Set<Path> fullyLoaded = new HashSet<>();        // months whose articles are in memory
    private int damagedLines;
    private long loadedSignature = -1;

    public ArticleStore() { this(AppPaths.dataDir()); }
    public ArticleStore(Path dir) { this.dir = dir; }

    // ------------------------------------------------------------------ loading

    public synchronized void load() throws IOException {
        byId.clear(); ids.clear(); titleKeys.clear(); offsets.clear(); fullyLoaded.clear();
        damagedLines = 0;
        YearMonth cutoff = YearMonth.now(zone).minusMonths(RECENT_MONTHS - 1);
        for (Path f : files()) {
            boolean full = !monthOf(f).isBefore(cutoff);
            readFrom(f, full);
            if (full) fullyLoaded.add(f);
        }
        loadedSignature = signature();
    }

    /** Reads only what other processes (the daily task) appended since the last read. */
    public synchronized boolean reloadIfChanged() throws IOException {
        long sig = signature();
        if (sig == loadedSignature) return false;
        for (Path f : files()) {
            boolean isNew = !offsets.containsKey(f);
            readFrom(f, isNew || fullyLoaded.contains(f));
            if (isNew) fullyLoaded.add(f);
        }
        loadedSignature = signature();
        return true;
    }

    /** Makes sure every month that can hold articles published at or after {@code t} is in memory. */
    public synchronized void ensureLoaded(long t) throws IOException {
        YearMonth from = YearMonth.from(Instant.ofEpochMilli(Math.max(0, t - 3_600_000L)).atZone(zone));
        for (Path f : files()) {
            if (fullyLoaded.contains(f) || monthOf(f).isBefore(from)) continue;
            offsets.remove(f);
            readFrom(f, true);
            fullyLoaded.add(f);
        }
    }

    private void readFrom(Path f, boolean full) throws IOException {
        long start = offsets.getOrDefault(f, 0L);
        long size = Files.size(f);
        if (size <= start) return;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            ch.position(start);
            InputStream in = java.nio.channels.Channels.newInputStream(ch);
            ByteArrayOutputStream line = new ByteArrayOutputStream(512);
            byte[] buf = new byte[1 << 16];
            long pos = start, lineStart = start;
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    pos++;
                    if (b == '\n') {
                        accept(line.toString(StandardCharsets.UTF_8), full);
                        line.reset();
                        lineStart = pos;
                    } else {
                        line.write(b);
                    }
                }
            }
            // A trailing piece without a newline is either being written right now or was cut off by a
            // crash. Leave it unread; it is counted as damaged only if it is still there next time we write.
            offsets.put(f, lineStart);
        }
    }

    private void accept(String line, boolean full) {
        if (line.isEmpty()) return;
        Article a = decode(line);
        if (a == null) { damagedLines++; return; }
        long tk = titleKey(a);
        if (ids.contains(a.id) || titleKeys.containsKey(tk)) {
            // Already indexed (e.g. an older month now being loaded in full): load it if it is the stored copy.
            if (full && !byId.containsKey(a.id) && a.id.equals(titleKeys.get(tk))) byId.put(a.id, a);
            return;
        }
        ids.add(a.id);
        titleKeys.put(tk, a.id);
        if (full) byId.put(a.id, a);
    }

    // ------------------------------------------------------------------ writing

    /** Adds articles not seen before, writes them to disk, and returns the new ones. */
    public synchronized List<Article> addAll(Collection<Article> incoming) throws IOException {
        List<Article> fresh = new ArrayList<>();
        for (Article a : incoming) {
            long tk = titleKey(a);
            if (ids.contains(a.id) || titleKeys.containsKey(tk)) continue;
            ids.add(a.id);
            titleKeys.put(tk, a.id);
            byId.put(a.id, a);
            fresh.add(a);
        }
        if (fresh.isEmpty()) return fresh;
        Map<Path, StringBuilder> byFile = new LinkedHashMap<>();
        for (Article a : fresh) {
            Path f = fileFor(a.fetched);
            byFile.computeIfAbsent(f, k -> new StringBuilder()).append(encode(a)).append('\n');
        }
        Files.createDirectories(dir);
        try (FileChannel lockCh = FileChannel.open(dir.resolve(".write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockCh.lock()) {
            for (var e : byFile.entrySet()) {
                Path f = e.getKey();
                try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
                    long size = ch.size();
                    byte[] data = e.getValue().toString().getBytes(StandardCharsets.UTF_8);
                    if (size > 0 && !endsWithNewline(ch, size)) {
                        // Repair a line cut off by an earlier crash so the new data starts on its own line.
                        damagedLines++;
                        byte[] fixed = new byte[data.length + 1];
                        fixed[0] = '\n';
                        System.arraycopy(data, 0, fixed, 1, data.length);
                        data = fixed;
                    }
                    ch.position(size);
                    ByteBuffer bb = ByteBuffer.wrap(data);
                    while (bb.hasRemaining()) ch.write(bb);
                    ch.force(true);
                    offsets.put(f, ch.size());
                }
                fullyLoaded.add(f);
            }
        }
        backupIfDue();
        loadedSignature = signature();
        return fresh;
    }

    private static boolean endsWithNewline(FileChannel ch, long size) throws IOException {
        ByteBuffer last = ByteBuffer.allocate(1);
        ch.read(last, size - 1);
        return last.get(0) == '\n';
    }

    private void backupIfDue() {
        try {
            Path current = fileFor(System.currentTimeMillis());
            if (!Files.exists(current)) return;
            Path bdir = dir.resolve("backup");
            Path b = bdir.resolve(current.getFileName() + ".bak");
            if (Files.exists(b) && System.currentTimeMillis() - Files.getLastModifiedTime(b).toMillis() < BACKUP_EVERY_MS) return;
            Files.createDirectories(bdir);
            Path tmp = bdir.resolve(current.getFileName() + ".tmp");
            Files.copy(current, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, b, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(b, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            System.err.println("Backup skipped: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries

    /** Articles published at or after {@code t}, loading older months from disk if needed. */
    public synchronized List<Article> since(long t) {
        try { ensureLoaded(t); } catch (IOException e) { System.err.println("Could not load older months: " + e.getMessage()); }
        List<Article> out = new ArrayList<>();
        for (Article a : byId.values()) if (a.published >= t) out.add(a);
        return out;
    }

    public synchronized List<Article> all() { return since(0); }

    /**
     * Streams every article, from memory for loaded months and straight from disk for older ones
     * (without keeping them), passing those that match to {@code out}. Used by archive search.
     */
    public void scan(Predicate<Article> match, Consumer<Article> out) throws IOException {
        scan(line -> true, match, out);
    }

    /**
     * As {@link #scan(Predicate, Consumer)}, with a quick test on each raw archive line ({@code lineMayMatch})
     * that lets most non-matching lines be skipped without decoding. Months are scanned in parallel.
     */
    public void scan(Predicate<String> lineMayMatch, Predicate<Article> match, Consumer<Article> out) throws IOException {
        List<Article> mem;
        List<Path> onDisk = new ArrayList<>();
        synchronized (this) {
            mem = new ArrayList<>(byId.values());
            for (Path f : files()) if (!fullyLoaded.contains(f)) onDisk.add(f);
        }
        Consumer<Article> sink = a -> { synchronized (out) { out.accept(a); } };
        mem.parallelStream().filter(match).forEach(sink);
        IOException[] err = new IOException[1];
        onDisk.parallelStream().forEach(f -> {
            try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
                lines.filter(lineMayMatch).forEach(l -> {
                    Article a = decode(l);
                    if (a != null && match.test(a)) sink.accept(a);
                });
            } catch (IOException | java.io.UncheckedIOException e) {
                err[0] = e instanceof IOException io ? io : new IOException(e);
            }
        });
        if (err[0] != null) throw err[0];
    }

    public synchronized Article get(String id) { return byId.get(id); }

    /** The id under which this article (or the same headline from the same outlet) is stored. */
    public synchronized String canonicalId(Article a) {
        if (ids.contains(a.id)) return a.id;
        String byTitle = titleKeys.get(titleKey(a));
        return byTitle != null ? byTitle : a.id;
    }

    /** Total number of distinct articles saved, across all history. */
    public synchronized int size() { return ids.size(); }

    /** Lines that could not be read (for example cut off by a crash). */
    public synchronized int damagedLines() { return damagedLines; }

    public synchronized int loadedCount() { return byId.size(); }

    // ------------------------------------------------------------------ files

    private Path fileFor(long epochMillis) {
        return dir.resolve("articles-" + MONTH.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) + ".tsv");
    }

    private static YearMonth monthOf(Path f) {
        String n = f.getFileName().toString();
        try { return YearMonth.parse(n.substring(9, 16)); } catch (RuntimeException e) { return YearMonth.of(1970, 1); }
    }

    private List<Path> files() throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "articles-*.tsv")) {
            for (Path p : ds) out.add(p);
        }
        out.sort(null);
        return out;
    }

    private long signature() {
        long sig = 17;
        try {
            for (Path p : files()) sig = sig * 31 + Files.size(p) + p.getFileName().hashCode();
        } catch (IOException e) {
            return -2;
        }
        return sig;
    }

    // ------------------------------------------------------------------ line format

    static long titleKey(Article a) {
        String k = a.source.toLowerCase(Locale.ROOT) + "|" + a.title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        long h = 1125899906842597L;
        for (int i = 0; i < k.length(); i++) h = 31 * h + k.charAt(i);
        return h;
    }

    static String encode(Article a) {
        return String.join("\t", esc(a.id), esc(a.url), esc(a.title), esc(a.summary), esc(a.source),
                a.feedCategory.name(), Long.toString(a.published), Long.toString(a.fetched));
    }

    static Article decode(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 8) return null;
        try {
            if (p[0].isEmpty() || p[2].isEmpty()) return null;
            return new Article(unesc(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]), unesc(p[4]),
                    Category.parse(p[5]), Long.parseLong(p[6]), Long.parseLong(p[7]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
