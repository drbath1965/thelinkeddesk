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
        if (compressed(f)) {
            // A compressed month never changes: read it once, whole.
            if (offsets.containsKey(f)) return;
            try (java.io.BufferedReader r = reader(f)) {
                String l;
                while ((l = r.readLine()) != null) accept(l, full);
            }
            offsets.put(f, Files.size(f));
            return;
        }
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
            try (java.io.BufferedReader r = reader(f)) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (!lineMayMatch.test(l)) continue;
                    Article a = decode(l);
                    if (a != null && match.test(a)) sink.accept(a);
                }
            } catch (IOException | java.io.UncheckedIOException e) {
                err[0] = e instanceof IOException io ? io : new IOException(e);
            }
        });
        if (err[0] != null) throw err[0];
    }

    public synchronized Article get(String id) { return byId.get(id); }

    private SearchIndex index;

    /** The full-text index over this archive (STO-F03). */
    public synchronized SearchIndex index() {
        if (index == null) index = new SearchIndex(this);
        return index;
    }

    /**
     * Every article matching the query (each once), using the full-text index where it can and scanning where it
     * can't. {@code match} is the full test; it decides, the index only saves reading.
     */
    public void find(com.linkeddesk.analysis.SearchQuery q, Predicate<Article> match, Consumer<Article> out) throws IOException {
        Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Consumer<Article> once = a -> { if (seen.add(a.id)) out.accept(a); };
        if (!index().find(q, match, once)) scan(line -> !q.cannotMatch(line), match, once);
    }

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

    public static YearMonth monthOf(Path f) {
        String n = f.getFileName().toString();
        try { return YearMonth.parse(n.substring(9, 16)); } catch (RuntimeException e) { return YearMonth.of(1970, 1); }
    }

    /**
     * The monthly files, oldest first: articles-YYYY-MM.tsv, or articles-YYYY-MM.tsv.gz once compressed. (If both
     * exist for a month, both are read; repeated articles are recognised by their id.)
     */
    public List<Path> files() throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "articles-*.tsv{,.gz}")) {
            for (Path p : ds) if (p.getFileName().toString().length() >= 20) out.add(p);
        }
        out.sort(null);
        return out;
    }

    public Path dir() { return dir; }

    static boolean compressed(Path f) { return f.getFileName().toString().endsWith(".gz"); }

    /** Reads a monthly file, compressed or not. */
    public static java.io.BufferedReader reader(Path f) throws IOException { return reader(f, compressed(f)); }

    static java.io.BufferedReader reader(Path f, boolean gz) throws IOException {
        InputStream in = Files.newInputStream(f);
        if (gz) in = new java.util.zip.GZIPInputStream(in, 1 << 16);
        return new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    // ------------------------------------------------------------------ compressing old months (STO-F04)

    /** Months are written as gzip members of about this many bytes, so one article can be read without unpacking the month. */
    static final int BLOCK = 1 << 16;

    /**
     * Compresses every month older than {@code keepMonths} months (articles-YYYY-MM.tsv -> .tsv.gz, about a fifth of
     * the size). Reading stays transparent. Returns the months compressed.
     */
    public List<String> compressOlderThan(int keepMonths) throws IOException {
        YearMonth cutoff = YearMonth.now(zone).minusMonths(keepMonths);
        List<String> done = new ArrayList<>();
        for (Path f : files()) {
            if (compressed(f) || !monthOf(f).isBefore(cutoff)) continue;
            compress(f);
            done.add(monthOf(f).toString());
        }
        return done;
    }

    /** Compresses one month's file (keeping anything an earlier compressed copy of the month already holds). */
    synchronized void compress(Path tsv) throws IOException {
        Path gz = tsv.resolveSibling(tsv.getFileName() + ".gz");
        Path tmp = tsv.resolveSibling(tsv.getFileName() + ".gz.tmp");
        Path blocks = blocksFile(gz);
        Files.createDirectories(dir);
        try (FileChannel lockCh = FileChannel.open(dir.resolve(".write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockCh.lock()) {
            if (!Files.exists(tsv)) return;
            StringBuilder table = new StringBuilder();
            try (java.io.OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
                CountingOut counted = new CountingOut(out);
                long[] uncompressed = {0};
                ByteArrayOutputStream block = new ByteArrayOutputStream(BLOCK + 8192);
                java.util.function.Consumer<String> line = l -> {
                    byte[] b = (l + "\n").getBytes(StandardCharsets.UTF_8);
                    block.writeBytes(b);
                };
                Runnable flush = () -> {
                    if (block.size() == 0) return;
                    try {
                        table.append(uncompressed[0]).append('\t').append(counted.count).append('\n');
                        java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(new NoClose(counted), 1 << 14);
                        block.writeTo(z);
                        z.finish();
                        uncompressed[0] += block.size();
                        block.reset();
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                };
                try {
                    if (Files.exists(gz)) try (java.io.BufferedReader r = reader(gz)) {
                        String l;
                        while ((l = r.readLine()) != null) { if (l.isEmpty()) continue; line.accept(l); if (block.size() >= BLOCK) flush.run(); }
                    }
                    try (java.io.BufferedReader r = reader(tsv)) {
                        String l;
                        while ((l = r.readLine()) != null) {
                            if (l.isEmpty() || decode(l) == null) continue;     // a damaged line isn't carried over
                            line.accept(l);
                            if (block.size() >= BLOCK) flush.run();
                        }
                    }
                    flush.run();
                } catch (java.io.UncheckedIOException e) { throw e.getCause(); }
            }
            // Check the copy before the original goes: same articles, readable to the end.
            long want = countLines(tsv) + (Files.exists(gz) ? countLines(gz) : 0), got = countLines(tmp, true);
            if (got < want - damagedIn(tsv)) { Files.deleteIfExists(tmp); throw new IOException("Compressed copy of " + tsv.getFileName() + " is incomplete; the month was left as it was."); }
            Files.writeString(blocks, table.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(tsv);
            offsets.remove(tsv);
            offsets.put(gz, Files.size(gz));       // already read (as the .tsv)
            if (fullyLoaded.remove(tsv)) fullyLoaded.add(gz);
            loadedSignature = signature();
        }
    }

    static Path blocksFile(Path gz) { return gz.resolveSibling(gz.getFileName() + ".blocks"); }

    private static long countLines(Path f) throws IOException { return countLines(f, compressed(f)); }

    private static long countLines(Path f, boolean gz) throws IOException {
        long n = 0;
        try (java.io.BufferedReader r = reader(f, gz)) { String l; while ((l = r.readLine()) != null) if (!l.isEmpty()) n++; }
        return n;
    }

    private static long damagedIn(Path f) throws IOException {
        long n = 0;
        try (java.io.BufferedReader r = reader(f)) { String l; while ((l = r.readLine()) != null) if (!l.isEmpty() && decode(l) == null) n++; }
        return n;
    }

    private static final class CountingOut extends java.io.FilterOutputStream {
        long count;
        CountingOut(java.io.OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    private static final class NoClose extends java.io.FilterOutputStream {
        NoClose(java.io.OutputStream o) { super(o); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { flush(); }
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

    public static Article decode(String line) {
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
