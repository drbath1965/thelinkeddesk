package com.linkeddesk.store;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/**
 * Backup and restore (STO-F04). A backup is one zip file in a folder you choose (OneDrive works well):
 * the whole archive, your feeds, topics, watchlist, corrections and settings. Indexes, logs and temporary files
 * are left out (they're rebuilt). The newest few backups are kept.
 *
 * Restoring puts everything back as it was in the backup; what was there before is moved aside into
 * before-restore-&lt;date&gt;, not deleted.
 */
public final class Backup {
    private Backup() {}

    public static final String PREFIX = "LinkedDesk-backup-";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    static final String MANIFEST = "backup.txt";

    public record Info(Path file, String created, String version, long articles, int files, long bytes) {
        public String describe() {
            return String.format("%s: %,d articles, %d files, %.1f MB (made %s with version %s)",
                    file.getFileName(), articles, files, bytes / 1048576.0, created, version);
        }
    }

    /** Left out of backups: rebuilt or temporary. */
    static boolean skip(Path rel) {
        String s = rel.toString().replace('\\', '/');
        String name = rel.getFileName().toString();
        return s.startsWith("logs/") || s.startsWith("data/index/") || s.startsWith("data/backup/") || s.startsWith("before-restore-")
                || name.endsWith(".tmp") || name.endsWith(".lock") || name.startsWith(".write") || name.startsWith(".fetch")
                || name.startsWith(PREFIX);
    }

    /** Writes a backup of {@code home} into {@code folder}; keeps the newest {@code keep} backups there. */
    public static Info create(Path home, Path folder, String version, int keep) throws IOException {
        Files.createDirectories(folder);
        Path out = folder.resolve(PREFIX + STAMP.format(LocalDateTime.now()) + ".zip");
        Path tmp = folder.resolve(out.getFileName() + ".tmp");
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(home, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                if (!d.equals(home) && skip(home.relativize(d).resolve("x"))) return FileVisitResult.SKIP_SUBTREE;
                if (d.normalize().equals(folder.normalize())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && !skip(home.relativize(f))) files.add(f);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        long articles = 0, bytes = 0;
        Path data = home.resolve("data");
        Files.createDirectories(data);
        // Hold the archive's write lock so no download writes half a line into the copy.
        try (FileChannel lockCh = FileChannel.open(data.resolve(".write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockCh.lock();
             ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            for (Path f : files) {
                String rel = home.relativize(f).toString().replace('\\', '/');
                ZipEntry e = new ZipEntry(rel);
                e.setTime(Files.getLastModifiedTime(f).toMillis());
                if (rel.endsWith(".gz")) e.setMethod(ZipEntry.DEFLATED);
                z.putNextEntry(e);
                Files.copy(f, z);
                z.closeEntry();
                bytes += Files.size(f);
                if (rel.startsWith("data/articles-")) articles += countArticles(f);
            }
            String manifest = "The Linked Desk backup\ncreated=" + LocalDateTime.now().withNano(0) + "\nversion=" + version
                    + "\narticles=" + articles + "\nfiles=" + files.size() + "\nbytes=" + bytes + "\n";
            z.putNextEntry(new ZipEntry(MANIFEST));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        prune(folder, keep);
        return read(out);
    }

    static long countArticles(Path f) throws IOException {
        if (!f.getFileName().toString().matches("articles-\\d{4}-\\d{2}\\.tsv(\\.gz)?")) return 0;
        long n = 0;
        try (BufferedReader r = ArticleStore.reader(f)) {
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty() && ArticleStore.decode(l) != null) n++;
        }
        return n;
    }

    /** Backups in a folder, newest first. */
    public static List<Path> list(Path folder) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, PREFIX + "*.zip")) { for (Path p : ds) out.add(p); }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    private static void prune(Path folder, int keep) throws IOException {
        List<Path> all = list(folder);
        for (int i = Math.max(1, keep); i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    /** What a backup holds (from its manifest), after checking every file in it reads back correctly. */
    public static Info read(Path zip) throws IOException {
        Properties p = new Properties();
        int files = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip), 1 << 16))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(MANIFEST)) p.load(new InputStreamReader(new ByteArrayInputStream(z.readAllBytes()), StandardCharsets.UTF_8));
                else { while (z.read(buf) > 0) { } files++; }       // reading to the end checks the CRC
            }
        } catch (ZipException e) {
            throw new IOException(zip.getFileName() + " is damaged or not a backup: " + e.getMessage(), e);
        }
        if (p.isEmpty()) throw new IOException(zip.getFileName() + " is not a Linked Desk backup (no " + MANIFEST + ").");
        return new Info(zip, p.getProperty("created", "?"), p.getProperty("version", "?"), Long.parseLong(p.getProperty("articles", "0")),
                files, Long.parseLong(p.getProperty("bytes", "0")));
    }

    /**
     * Restores a backup into {@code home}. Files the backup replaces are first moved to before-restore-&lt;date&gt;.
     * Returns that folder (or null if nothing had to be moved).
     */
    public static Path restore(Path zip, Path home) throws IOException {
        Info info = read(zip);           // checks it fully before anything is touched
        Path aside = home.resolve("before-restore-" + STAMP.format(LocalDateTime.now()));
        boolean moved = false;
        Path data = home.resolve("data");
        Files.createDirectories(data);
        Path root = home.toAbsolutePath().normalize();
        try (FileChannel lockCh = FileChannel.open(data.resolve(".write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockCh.lock()) {
            // Move the current archive and settings aside (what the backup would replace, and every month file,
            // so the archive afterwards is exactly the backup's).
            List<Path> current = new ArrayList<>();
            Files.walkFileTree(home, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    return !d.equals(home) && skip(home.relativize(d).resolve("x")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile() && !skip(home.relativize(f))) current.add(f);
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path f : current) {
                Path to = aside.resolve(home.relativize(f));
                Files.createDirectories(to.getParent());
                Files.move(f, to, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
            try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip), 1 << 16))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || e.getName().equals(MANIFEST)) continue;
                    Path to = root.resolve(e.getName()).normalize();
                    if (!to.startsWith(root) || skip(root.relativize(to))) continue;      // never outside the data folder
                    Files.createDirectories(to.getParent());
                    Path tmp = to.resolveSibling(to.getFileName() + ".restore.tmp");
                    Files.copy(z, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
                    if (e.getTime() > 0) Files.setLastModifiedTime(to, java.nio.file.attribute.FileTime.fromMillis(e.getTime()));
                }
            }
        }
        // Indexes describe the old archive: drop them (they're rebuilt).
        Path idx = data.resolve("index");
        if (Files.isDirectory(idx)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(idx)) { for (Path p : ds) Files.deleteIfExists(p); }
        long now = 0;
        for (Path f : new ArticleStore(data).files()) now += countArticles(f);
        if (now < info.articles()) throw new IOException("Restore incomplete: " + now + " of " + info.articles() + " articles. Your previous files are in " + aside);
        return moved ? aside : null;
    }
}
