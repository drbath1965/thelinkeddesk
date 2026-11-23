package com.linkeddesk.store;

import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.model.Article;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A full-text index over the archive (STO-F03), so searching years of news takes well under a second.
 *
 * One index file per month in data/index (articles-YYYY-MM.idx): every word in the headline, summary and outlet
 * name, with the articles it occurs in. The monthly .tsv / .tsv.gz files stay the record; the index only says
 * where to look, and every article it points to is checked against the full query, so results are exactly those
 * of a full scan. A month without an up-to-date index is scanned as before, and anything appended after the index
 * was built (the current month, between rebuilds) is scanned too. The index can be deleted at any time; it is
 * rebuilt in the background.
 */
public final class SearchIndex {
    private static final int MAGIC = 0x4C445832;          // "LDX2"
    /** Rebuild the current month's index once this much has been added since the last build. */
    static final long TAIL_LIMIT = 2L << 20;

    private final ArticleStore store;
    private final Path dir;
    private final Map<String, Segment> cache = new HashMap<>();

    public SearchIndex(ArticleStore store) {
        this.store = store;
        this.dir = store.dir().resolve("index");
    }

    public Path dir() { return dir; }

    // ------------------------------------------------------------------ one month's index

    static final class Segment {
        Path file;
        String source;           // the monthly file's name
        long sourceSize;         // its size when indexed
        long covered;            // bytes of it indexed (whole lines; uncompressed offsets for .gz)
        int docs;
        long[] offsets;          // where each article's line starts (uncompressed)
        long[] published;        // each article's publication time (for newest-first results without reading them)
        String dict;             // the words, sorted, joined
        int[] starts;            // word i = dict[starts[i], starts[i + 1])
        long postings;           // where the postings start in the file
        int[] pOff, pLen;

        int terms() { return starts.length - 1; }

        int compare(int i, String key) {
            int a = starts[i], b = starts[i + 1], n = Math.min(b - a, key.length());
            for (int k = 0; k < n; k++) {
                int d = dict.charAt(a + k) - key.charAt(k);
                if (d != 0) return d;
            }
            return (b - a) - key.length();
        }

        boolean startsWith(int i, String prefix) {
            int a = starts[i], b = starts[i + 1];
            return b - a >= prefix.length() && dict.startsWith(prefix, a);
        }

        /** The first word >= key. */
        int lowerBound(String key) {
            int lo = 0, hi = terms();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (compare(mid, key) < 0) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }

    static Path indexFile(Path dir, Path source) {
        String n = source.getFileName().toString();
        return dir.resolve(n.substring(0, 16) + ".idx");
    }

    /** True if the article line's words and the query's words split the same way (letters and digits). */
    static boolean wordChar(int cp) {
        if (Character.isLetterOrDigit(cp)) return true;
        int t = Character.getType(cp);
        return t == Character.OTHER_NUMBER || t == Character.LETTER_NUMBER;
    }

    static List<String> tokens(String folded) {
        List<String> out = new ArrayList<>();
        int i = 0, n = folded.length();
        while (i < n) {
            int cp = folded.codePointAt(i);
            if (!wordChar(cp)) { i += Character.charCount(cp); continue; }
            int s = i;
            while (i < n && wordChar(folded.codePointAt(i))) i += Character.charCount(folded.codePointAt(i));
            out.add(folded.substring(s, i));
        }
        return out;
    }

    static String text(Article a) { return SearchQuery.foldText(a.title + " \n " + a.summary + " \n " + a.source); }

    /** Builds (or rebuilds) the index of one monthly file. */
    Segment build(Path source) throws IOException {
        Files.createDirectories(dir);
        long size = Files.size(source);
        Map<String, int[]> post = new HashMap<>(1 << 16);      // word -> [count, d0, d1, ...] growing
        long[] offsets = new long[1 << 12], published = new long[1 << 12];
        int docs = 0;
        long pos = 0, covered = 0;
        try (InputStream raw = Files.newInputStream(source);
             InputStream in = new BufferedInputStream(ArticleStore.compressed(source) ? new java.util.zip.GZIPInputStream(raw, 1 << 16) : raw, 1 << 16)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
            long lineStart = 0;
            int b;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    b = buf[i];
                    pos++;
                    if (b != '\n') { line.write(b); continue; }
                    Article a = line.size() == 0 ? null : ArticleStore.decode(line.toString(StandardCharsets.UTF_8));
                    if (a != null) {
                        if (docs == offsets.length) { offsets = Arrays.copyOf(offsets, docs * 2); published = Arrays.copyOf(published, docs * 2); }
                        offsets[docs] = lineStart;
                        published[docs] = a.published;
                        for (String t : new HashSet<>(tokens(text(a)))) {
                            int[] p = post.get(t);
                            if (p == null) { p = new int[4]; post.put(t, p); }
                            else if (p[0] + 1 == p.length) { p = Arrays.copyOf(p, p.length * 2); post.put(t, p); }
                            p[++p[0]] = docs;
                        }
                        docs++;
                    }
                    line.reset();
                    lineStart = pos;
                    covered = pos;
                }
            }
        }
        String[] words = post.keySet().toArray(new String[0]);
        Arrays.sort(words);
        Path out = indexFile(dir, source);
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            o.writeInt(MAGIC);
            o.writeUTF(source.getFileName().toString());
            o.writeLong(size);
            o.writeLong(covered);
            o.writeInt(docs);
            for (int i = 0; i < docs; i++) o.writeLong(offsets[i]);
            for (int i = 0; i < docs; i++) o.writeLong(published[i]);
            o.writeInt(words.length);
            ByteArrayOutputStream pbytes = new ByteArrayOutputStream(1 << 20);
            int[] off = new int[words.length], len = new int[words.length];
            for (int w = 0; w < words.length; w++) {
                int[] p = post.get(words[w]);
                off[w] = pbytes.size();
                int prev = -1;
                for (int k = 1; k <= p[0]; k++) { varint(pbytes, p[k] - prev); prev = p[k]; }
                len[w] = pbytes.size() - off[w];
            }
            byte[] dictBytes = String.join("\n", words).getBytes(StandardCharsets.UTF_8);
            o.writeInt(dictBytes.length);
            o.write(dictBytes);
            for (int w = 0; w < words.length; w++) { o.writeInt(off[w]); o.writeInt(len[w]); }
            o.writeInt(pbytes.size());
            pbytes.writeTo(o);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        synchronized (cache) { cache.remove(out.getFileName().toString()); }
        return load(out);
    }

    private static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    static Segment load(Path f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not an index file: " + f);
            Segment s = new Segment();
            s.file = f;
            s.source = in.readUTF();
            s.sourceSize = in.readLong();
            s.covered = in.readLong();
            s.docs = in.readInt();
            s.offsets = new long[s.docs];
            for (int i = 0; i < s.docs; i++) s.offsets[i] = in.readLong();
            s.published = new long[s.docs];
            for (int i = 0; i < s.docs; i++) s.published[i] = in.readLong();
            int words = in.readInt();
            int dl = in.readInt();
            byte[] db = in.readNBytes(dl);
            if (db.length != dl) throw new EOFException();
            String joined = new String(db, StandardCharsets.UTF_8);
            s.starts = new int[words + 1];
            StringBuilder dict = new StringBuilder(joined.length());
            int w = 0, from = 0;
            if (words > 0) {
                for (int i = 0; i <= joined.length(); i++) {
                    if (i == joined.length() || joined.charAt(i) == '\n') {
                        s.starts[w++] = dict.length();
                        dict.append(joined, from, i);
                        from = i + 1;
                    }
                }
            }
            s.starts[words] = dict.length();
            s.dict = dict.toString();
            s.pOff = new int[words];
            s.pLen = new int[words];
            for (int i = 0; i < words; i++) { s.pOff[i] = in.readInt(); s.pLen[i] = in.readInt(); }
            in.readInt();
            // Postings follow: their position in the file.
            s.postings = 4 + 2 + s.source.getBytes(StandardCharsets.UTF_8).length + 8 + 8 + 4 + 16L * s.docs + 4 + 4 + dl + 8L * words + 4;
            return s;
        }
    }

    /** The month's index if it can be used for this file now (it may cover only the start of a growing month). */
    Segment segment(Path source) {
        Path f = indexFile(dir, source);
        String key = f.getFileName().toString();
        Segment s;
        synchronized (cache) { s = cache.get(key); }
        try {
            if (s == null || !Files.exists(f) || Files.getLastModifiedTime(f).toMillis() > lastLoaded.getOrDefault(key, 0L)) {
                if (!Files.exists(f)) return null;
                long t = Files.getLastModifiedTime(f).toMillis();
                s = load(f);
                synchronized (cache) { cache.put(key, s); lastLoaded.put(key, t); }
            }
            if (!s.source.equals(source.getFileName().toString())) return null;      // e.g. the month was compressed since
            long size = Files.size(source);
            if (ArticleStore.compressed(source) ? size != s.sourceSize : size < s.covered) return null;
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    private final Map<String, Long> lastLoaded = new HashMap<>();

    /** Months whose index is missing, out of date, or far behind: what {@link #refresh} would build. */
    public List<Path> stale() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path f : store.files()) {
            Segment s = segment(f);
            if (s == null) out.add(f);
            else if (!ArticleStore.compressed(f) && Files.size(f) - s.covered > TAIL_LIMIT) out.add(f);
        }
        return out;
    }

    /** Builds what's missing or behind (the first time: every month), and removes indexes of months that are gone. */
    public int refresh() throws IOException {
        List<Path> todo = stale();
        for (Path f : todo) build(f);
        Set<String> keep = new HashSet<>();
        for (Path f : store.files()) keep.add(indexFile(dir, f).getFileName().toString());
        if (Files.isDirectory(dir)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.idx")) {
            for (Path p : ds) if (!keep.contains(p.getFileName().toString())) Files.deleteIfExists(p);
        }
        return todo.size();
    }

    /** Index files and their size, for the status line in Tools > Archive. */
    public long[] stats() throws IOException {
        long n = 0, bytes = 0;
        if (Files.isDirectory(dir)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.idx")) {
            for (Path p : ds) { n++; bytes += Files.size(p); }
        }
        return new long[]{n, bytes};
    }

    // ------------------------------------------------------------------ searching

    /**
     * Passes every article that matches to {@code out}: through the index where a month has one, by scanning
     * otherwise. {@code match} is the full test (words, filters); the index only narrows where to apply it.
     * Returns false if the query has no words the index can use (the caller scans instead).
     */
    public boolean find(SearchQuery q, Predicate<Article> match, Consumer<Article> out) throws IOException {
        List<List<String>> groups = q.wordGroups();
        if (groups.isEmpty()) return false;
        for (List<String> g : groups) {
            boolean usable = false;
            for (String w : g) if (!tokens(w).isEmpty()) usable = true;
            if (!usable) return false;
        }
        List<Path> files = store.files();
        IOException[] err = new IOException[1];
        Consumer<Article> sink = a -> { synchronized (out) { out.accept(a); } };
        files.parallelStream().forEach(f -> {
            try { findIn(f, groups, q, match, sink); }
            catch (IOException e) { synchronized (err) { err[0] = e; } }
            catch (UncheckedIOException e) { synchronized (err) { err[0] = e.getCause(); } }
        });
        if (err[0] != null) throw err[0];
        return true;
    }

    private void findIn(Path f, List<List<String>> groups, SearchQuery q, Predicate<Article> match, Consumer<Article> out) throws IOException {
        Segment s = segment(f);
        if (s == null) {           // no index yet for this month: scan it
            try (BufferedReader r = ArticleStore.reader(f)) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (q.cannotMatch(l)) continue;
                    Article a = resolve(ArticleStore.decode(l));
                    if (a != null && match.test(a)) out.accept(a);
                }
            }
            return;
        }
        BitSet all = candidates(s, groups);
        // Check each candidate against the full query.
        try (LineReader lines = new LineReader(f)) {
            for (int d = all.nextSetBit(0); d >= 0; d = all.nextSetBit(d + 1)) {
                String l = lines.lineAt(s.offsets[d]);
                Article a = l == null ? null : resolve(ArticleStore.decode(l));
                if (a != null && match.test(a)) out.accept(a);
            }
            // Added since the index was built (a growing month): scan that part.
            if (!ArticleStore.compressed(f)) for (String l : lines.from(s.covered)) {
                if (q.cannotMatch(l)) continue;
                Article a = resolve(ArticleStore.decode(l));
                if (a != null && match.test(a)) out.accept(a);
            }
        }
    }

    /** The copy in memory if the month is loaded (it carries the desk the app gave it), else the one from disk. */
    private Article resolve(Article a) {
        if (a == null) return null;
        Article m = store.get(a.id);
        return m != null ? m : a;
    }

    /** The articles that may match: per OR-group, those holding every word of the group. */
    private static BitSet candidates(Segment s, List<List<String>> groups) throws IOException {
        BitSet all = new BitSet(s.docs);
        try (FileChannel idx = FileChannel.open(s.file, StandardOpenOption.READ)) {
            for (List<String> g : groups) {
                BitSet group = null;
                for (String w : g) {
                    List<String> toks = tokens(w);
                    if (toks.isEmpty()) continue;
                    for (int t = 0; t < toks.size(); t++) {
                        BitSet docs = lookup(s, idx, toks.get(t), t == toks.size() - 1);
                        if (group == null) group = docs; else group.and(docs);
                        if (group.isEmpty()) break;
                    }
                    if (group != null && group.isEmpty()) break;
                }
                if (group != null) all.or(group);
            }
        }
        return all;
    }

    /** What {@link #newest} found: every match counted, the newest ones read. */
    public record Newest(int total, List<Article> newest) {}

    /**
     * For a query of plain words (no phrases, no -words, no filters): the index alone decides which articles match
     * (a word matches at the start of a word in the text, exactly as the full test does), so only the newest
     * {@code cap} of them need reading. Returns null for any other query (use {@link #find}).
     */
    public Newest newest(SearchQuery q, int cap, long from, long to) throws IOException {
        if (q.hasExcludes() || !q.fields().isEmpty() || !q.hasWords()) return null;
        List<List<String>> groups = q.wordGroups();
        for (List<String> g : groups) for (String w : g) {
            List<String> t = tokens(w);
            if (t.size() != 1 || !t.get(0).equals(w)) return null;
        }
        record Hit(long published, Path file, long offset) {}
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        List<Article> scanned = Collections.synchronizedList(new ArrayList<>());
        IOException[] err = new IOException[1];
        java.util.function.LongPredicate inTime = t -> (from <= 0 || t >= from) && (to <= 0 || t <= to);
        Predicate<Article> match = a -> inTime.test(a.published) && q.matches(a);
        store.files().parallelStream().forEach(f -> {
            try {
                Segment s = segment(f);
                if (s == null) { findIn(f, groups, q, match, scanned::add); return; }
                BitSet c = candidates(s, groups);
                List<Hit> mine = new ArrayList<>(c.cardinality());
                for (int d = c.nextSetBit(0); d >= 0; d = c.nextSetBit(d + 1)) if (inTime.test(s.published[d])) mine.add(new Hit(s.published[d], f, s.offsets[d]));
                hits.addAll(mine);
                if (!ArticleStore.compressed(f) && Files.size(f) > s.covered) try (LineReader lines = new LineReader(f)) {
                    for (String l : lines.from(s.covered)) {
                        if (q.cannotMatch(l)) continue;
                        Article a = resolve(ArticleStore.decode(l));
                        if (a != null && match.test(a)) scanned.add(a);
                    }
                }
            } catch (IOException e) { synchronized (err) { err[0] = e; } }
        });
        if (err[0] != null) throw err[0];
        List<Hit> all = new ArrayList<>(hits);
        all.sort(Comparator.comparingLong(Hit::published).reversed());
        List<Article> out = new ArrayList<>(scanned);
        // Read the newest few (a little over the cap, in case some are repeats), month by month in file order.
        Map<Path, List<Hit>> byFile = new TreeMap<>();
        for (Hit h : all.subList(0, Math.min(all.size(), cap + cap / 10 + 20))) byFile.computeIfAbsent(h.file(), k -> new ArrayList<>()).add(h);
        for (var e : byFile.entrySet()) {
            e.getValue().sort(Comparator.comparingLong(Hit::offset));
            try (LineReader lines = new LineReader(e.getKey())) {
                for (Hit h : e.getValue()) {
                    String l = lines.lineAt(h.offset());
                    Article a = l == null ? null : resolve(ArticleStore.decode(l));
                    if (a != null) out.add(a);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        out.removeIf(a -> !seen.add(a.id));
        out.sort(Comparator.comparingLong((Article a) -> a.published).reversed());
        int total = all.size() + scanned.size();
        return new Newest(total, out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out);
    }

    /** The articles containing this word (exact) or a word starting with it (prefix). */
    private static BitSet lookup(Segment s, FileChannel idx, String word, boolean prefix) throws IOException {
        BitSet out = new BitSet(s.docs);
        int i = s.lowerBound(word);
        while (i < s.terms() && (prefix ? s.startsWith(i, word) : s.compare(i, word) == 0)) {
            ByteBuffer bb = ByteBuffer.allocate(s.pLen[i]);
            long at = s.postings + s.pOff[i];
            while (bb.hasRemaining()) { if (idx.read(bb, at + bb.position()) < 0) throw new EOFException(); }
            byte[] b = bb.array();
            int doc = -1, k = 0;
            while (k < b.length) {
                int v = 0, shift = 0, x;
                do { x = b[k++]; v |= (x & 0x7F) << shift; shift += 7; } while ((x & 0x80) != 0);
                doc += v;
                out.set(doc);
            }
            if (!prefix) break;
            i++;
        }
        return out;
    }

    /** Reads single lines of a monthly file by (uncompressed) offset; compressed months a block at a time. */
    static final class LineReader implements Closeable {
        private final Path f;
        private final boolean gz;
        private FileChannel ch;
        private long[] uStart, cStart;    // gzip blocks: uncompressed and compressed starts
        private int blockNo = -1;
        private byte[] block;              // the current block (or the whole month without a block table)

        LineReader(Path f) throws IOException {
            this.f = f;
            this.gz = ArticleStore.compressed(f);
            ch = FileChannel.open(f, StandardOpenOption.READ);
            if (gz) readBlocks();
        }

        private void readBlocks() {
            Path bf = ArticleStore.blocksFile(f);
            try {
                if (!Files.exists(bf)) return;
                List<String> ls = Files.readAllLines(bf, StandardCharsets.UTF_8);
                long size = Files.size(f);
                uStart = new long[ls.size()];
                cStart = new long[ls.size()];
                for (int i = 0; i < ls.size(); i++) {
                    String[] p = ls.get(i).split("\t");
                    uStart[i] = Long.parseLong(p[0]);
                    cStart[i] = Long.parseLong(p[1]);
                    if (cStart[i] >= size || (i > 0 && (cStart[i] <= cStart[i - 1] || uStart[i] <= uStart[i - 1]))) throw new IOException("bad block table");
                }
                if (uStart.length == 0 || uStart[0] != 0 || cStart[0] != 0) throw new IOException("bad block table");
            } catch (Exception e) { uStart = null; cStart = null; }
        }

        private byte[] win = new byte[0];
        private long winStart = -1;
        private int winLen;

        String lineAt(long offset) throws IOException {
            if (!gz) {
                // Candidates come in file order, often close together: read 256 KB windows rather than line by line.
                if (winStart < 0 || offset < winStart || offset >= winStart + winLen || indexOf(win, (int) (offset - winStart), winLen) < 0) {
                    if (!fill(offset, 1 << 18)) return null;
                    if (indexOf(win, 0, winLen) < 0) {            // a very long line: read until its end
                        int size = 1 << 19;
                        while (true) {
                            if (!fill(offset, size)) return null;
                            if (indexOf(win, 0, winLen) >= 0) break;
                            if (winLen < size) return null;   // cut off: not a whole line
                            size *= 2;
                        }
                    }
                }
                int at = (int) (offset - winStart), e = indexOf(win, at, winLen);
                return new String(win, at, e - at, StandardCharsets.UTF_8);
            }
            long base;
            if (uStart == null) {
                if (block == null) block = unpack(0, Files.size(f));
                base = 0;
            } else {
                int b = Arrays.binarySearch(uStart, offset);
                if (b < 0) b = -b - 2;
                if (b != blockNo) {
                    long end = b + 1 < cStart.length ? cStart[b + 1] : Files.size(f);
                    try { block = unpack(cStart[b], end); blockNo = b; }
                    catch (IOException e) { uStart = null; block = null; blockNo = -1; return lineAt(offset); }  // damaged table: unpack it all
                }
                base = uStart[b];
            }
            int at = (int) (offset - base);
            if (at < 0 || at >= block.length) return null;
            int e = at;
            while (e < block.length && block[e] != '\n') e++;
            return new String(block, at, e - at, StandardCharsets.UTF_8);
        }

        private static int indexOf(byte[] a, int from, int to) {
            for (int i = from; i < to; i++) if (a[i] == '\n') return i;
            return -1;
        }

        private boolean fill(long offset, int size) throws IOException {
            if (win.length < size) win = new byte[size];
            ByteBuffer bb = ByteBuffer.wrap(win, 0, size);
            while (bb.hasRemaining()) { if (ch.read(bb, offset + bb.position()) <= 0) break; }
            winStart = offset;
            winLen = bb.position();
            return winLen > 0;
        }

        private byte[] unpack(long from, long to) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate((int) (to - from));
            while (bb.hasRemaining()) { if (ch.read(bb, from + bb.position()) < 0) break; }
            try (InputStream in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bb.array(), 0, bb.position()), 1 << 15)) {
                return in.readAllBytes();
            }
        }

        /** Whole lines from this offset to the end (the part of a growing month the index doesn't cover yet). */
        List<String> from(long offset) throws IOException {
            List<String> out = new ArrayList<>();
            if (gz || ch.size() <= offset) return out;   // (compressed months are always fully indexed)
            ByteBuffer bb = ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 16, ch.size() - offset));
            while (bb.hasRemaining()) { if (ch.read(bb, offset + bb.position()) < 0) break; }
            byte[] a = bb.array();
            int s = 0;
            for (int i = 0; i < bb.position(); i++) {
                if (a[i] == '\n') { if (i > s) out.add(new String(a, s, i - s, StandardCharsets.UTF_8)); s = i + 1; }
            }
            return out;
        }

        @Override public void close() throws IOException { if (ch != null) ch.close(); }
    }
}
