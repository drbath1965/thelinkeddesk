package com.linkeddesk.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reading and writing the app's own files without losing them (OPS-F08). A read is retried a few times (antivirus
 * or a sync tool may hold a file for a moment); a damaged file is read from the {@code .bak} copy kept by the last write. If a
 * file still can't be read, the caller remembers that and refuses to save over it, so a list that came back empty
 * because of a read error is never written back as if it were the truth. Every write keeps the previous version as
 * {@code .bak} and replaces the file in one step.
 */
public final class SafeFiles {
    private SafeFiles() {}

    /** A file that exists but couldn't be read, even from its backup. */
    public static final class Unreadable extends IOException {
        public Unreadable(Path f, IOException cause) { super("Couldn't read " + f.getFileName() + ": " + cause.getMessage(), cause); }
    }

    private static final List<String> PROBLEMS = Collections.synchronizedList(new ArrayList<>());
    static int retries = 3;
    static long pauseMs = 150;

    /** Recent read and write problems (for Check this PC), newest last. */
    public static List<String> problems() { synchronized (PROBLEMS) { return List.copyOf(PROBLEMS); } }

    static void problem(String s) {
        synchronized (PROBLEMS) { PROBLEMS.add(s); if (PROBLEMS.size() > 50) PROBLEMS.remove(0); }
        Logs.error(s, null);
    }

    public static Path backup(Path f) { return f.resolveSibling(f.getFileName() + ".bak"); }

    /** The file's text; "" if it doesn't exist. Retries, then tries the backup; throws {@link Unreadable} if both fail. */
    public static String readString(Path f) throws Unreadable {
        if (!Files.exists(f)) return "";
        IOException last = null;
        boolean damaged = false;
        for (int i = 0; i < retries; i++) {
            try { return Files.readString(f, StandardCharsets.UTF_8); }
            catch (java.nio.charset.CharacterCodingException bad) { last = bad; damaged = true; break; }   // damaged: go to the backup
            catch (IOException e) { last = e; sleep(); }
        }
        // Only a damaged file is replaced by its backup; one that's merely locked stays unread (and unsaved) until it can be read,
        // so a newer version is never overwritten with the older backup.
        Path bak = backup(f);
        if (damaged && Files.exists(bak)) {
            try {
                String s = Files.readString(bak, StandardCharsets.UTF_8);
                problem("Read " + f.getFileName() + " from its backup copy (" + last.getMessage() + ")");
                return s;
            } catch (IOException ignored) { /* fall through */ }
        }
        problem("Couldn't read " + f.getFileName() + " (" + last.getMessage() + "); it won't be saved over until it can be read");
        throw new Unreadable(f, last);
    }

    public static List<String> readLines(Path f) throws Unreadable {
        String s = readString(f);
        if (s.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>(List.of(s.split("\\r?\\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Writes the file in one step, keeping the previous version as .bak. */
    public static void write(Path f, String text) throws IOException {
        Path dir = f.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (Files.exists(f)) {
            try { Files.copy(f, backup(f), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { problem("Couldn't keep a backup of " + f.getFileName() + ": " + e.getMessage()); }
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Refuses a save when the file's last read failed (so nothing is written from an empty list by mistake). */
    public static void checkSafeToSave(boolean lastReadFailed, Path f) throws IOException {
        if (lastReadFailed)
            throw new IOException(f.getFileName() + " couldn't be read just now, so it wasn't changed (nothing was lost). Try again in a moment.");
    }

    private static void sleep() { try { Thread.sleep(pauseMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
