package com.linkeddesk.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs under logs/: one download log per month (fetch-YYYY-MM.log) and an error log with full stack
 * traces (errors.log, rolled over to errors.1.log at 5 MB). Each download starts with a line beginning
 * "=== " so the in-app log viewer can list runs.
 */
public final class Logs {
    static final long ERROR_LOG_MAX = 5L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private Logs() {}

    public static Path dir() {
        Path d = AppPaths.home().resolve("logs");
        try { Files.createDirectories(d); } catch (IOException ignored) { }
        return d;
    }

    /** Records one download run: a header line and the per-feed detail. */
    public static synchronized void run(String who, String summary, String detail, double seconds) {
        LocalDateTime now = LocalDateTime.now();
        String text = "=== " + now.format(STAMP) + " | " + who + " | " + summary + " | " + String.format("%.1f s", seconds)
                + System.lineSeparator() + detail.strip() + System.lineSeparator() + System.lineSeparator();
        append(dir().resolve("fetch-" + now.format(MONTH) + ".log"), text);
    }

    /** Records an error with its full stack trace. */
    public static synchronized void error(String what, Throwable t) {
        StringWriter sw = new StringWriter();
        sw.append(LocalDateTime.now().format(STAMP)).append("  ").append(what).append(System.lineSeparator());
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        Path f = dir().resolve("errors.log");
        try {
            if (Files.exists(f) && Files.size(f) > ERROR_LOG_MAX) {
                Files.move(f, dir().resolve("errors.1.log"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
        append(f, sw.append(System.lineSeparator()).toString());
    }

    private static void append(Path f, String text) {
        try {
            Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write log " + f + ": " + e.getMessage());
        }
    }

    /** One logged download run, for the log viewer. */
    public record Run(LocalDateTime when, String who, String summary, String duration, String detail) {}

    /** Runs from the last {@code days} days, newest first. */
    public static List<Run> recentRuns(int days) throws IOException {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir(), "fetch-*.log")) { for (Path p : ds) files.add(p); }
        files.sort(null);
        List<Run> runs = new ArrayList<>();
        for (Path f : files) {
            LocalDateTime when = null; String who = "", sum = "", dur = "";
            StringBuilder det = new StringBuilder();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("=== ")) {
                    if (when != null && !when.isBefore(cutoff)) runs.add(new Run(when, who, sum, dur, det.toString().strip()));
                    String[] p = line.substring(4).split(" \\| ");
                    try { when = LocalDateTime.parse(p[0].trim(), STAMP); } catch (RuntimeException e) { when = null; }
                    who = p.length > 1 ? p[1] : ""; sum = p.length > 2 ? p[2] : ""; dur = p.length > 3 ? p[3] : "";
                    det.setLength(0);
                } else {
                    det.append(line).append('\n');
                }
            }
            if (when != null && !when.isBefore(cutoff)) runs.add(new Run(when, who, sum, dur, det.toString().strip()));
        }
        java.util.Collections.reverse(runs);                       // newest first, also within the same second
        runs.sort((a, b) -> b.when().compareTo(a.when()));
        return runs;
    }

    /** Errors logged since {@code since} (OPS-F09), with the first line of each, newest last. */
    public static List<String> errorsSince(LocalDateTime since) {
        List<String> out = new java.util.ArrayList<>();
        Path f = dir().resolve("errors.log");
        if (!Files.exists(f)) return out;
        try {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.length() < 21 || !Character.isDigit(l.charAt(0)) || l.charAt(4) != '-') continue;
                try {
                    LocalDateTime t = LocalDateTime.parse(l.substring(0, 19), STAMP);
                    if (!t.isBefore(since)) out.add(l.substring(19).strip());
                } catch (java.time.format.DateTimeParseException ignored) { /* a stack-trace line, not an entry */ }
            }
        } catch (IOException e) { out.add("errors.log couldn't be read: " + e.getMessage()); }
        return out;
    }

    public static String errorLogText() {
        try {
            Path f = dir().resolve("errors.log");
            if (!Files.exists(f)) return "No errors recorded.";
            String s = Files.readString(f, StandardCharsets.UTF_8);
            return s.length() > 200_000 ? "…\n" + s.substring(s.length() - 200_000) : s;
        } catch (IOException e) {
            return "Could not read the error log: " + e.getMessage();
        }
    }
}
