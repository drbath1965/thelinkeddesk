package com.linkeddesk.store;

import com.linkeddesk.ai.Explainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Saved explainers (AI-F08): one text file per explainer in {@code explainers\}, named after the subject and time.
 * The header keeps the subject, level, time and the articles it cited; Claude's reply follows, then any follow-ups.
 * Plain text, so it can be read without the app, and it's included in backups with the rest of the data folder.
 */
public final class ExplainerStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private final Path dir;

    public ExplainerStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    public synchronized Path save(Explainer.Result r) throws IOException {
        Files.createDirectories(dir);
        Path f = file(r);
        StringBuilder sb = new StringBuilder("# The Linked Desk explainer\n");
        sb.append("subject\t").append(esc(r.subject())).append('\n')
          .append("level\t").append(r.level().name()).append('\n')
          .append("created\t").append(r.created()).append('\n')
          .append("previous\t").append(r.previousCreated()).append('\n');
        for (var e : r.refs().entrySet()) {
            Explainer.Source s = e.getValue();
            sb.append("ref\t").append(e.getKey()).append('\t').append(esc(s.id())).append('\t').append(esc(s.url())).append('\t').append(esc(s.source()))
              .append('\t').append(s.published()).append('\t').append(s.opinion()).append('\t').append(esc(s.title())).append('\n');
        }
        if (!r.weak().isEmpty()) sb.append("weak\t").append(String.join(",", r.weak())).append('\n');
        for (Explainer.FollowUp fu : r.followUps()) sb.append("followup\t").append(esc(fu.label())).append('\t').append(esc(fu.text())).append('\n');
        sb.append("---\n").append(r.raw());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return f;
    }

    public Path file(Explainer.Result r) { return dir.resolve(r.key() + "-" + STAMP.format(Instant.ofEpochMilli(r.created())) + ".txt"); }

    public static Explainer.Result read(Path f) throws IOException {
        String text = Files.readString(f, StandardCharsets.UTF_8);
        int split = text.indexOf("\n---\n");
        if (split < 0) throw new IOException("Not an explainer: " + f.getFileName());
        String subject = "";
        Explainer.Level level = Explainer.Level.DETAILED;
        long created = 0, previous = 0;
        Map<String, Explainer.Source> refs = new LinkedHashMap<>();
        List<Explainer.FollowUp> follow = new ArrayList<>();
        Set<String> weak = new LinkedHashSet<>();
        for (String l : text.substring(0, split).split("\n")) {
            String[] p = l.split("\t", -1);
            switch (p[0]) {
                case "subject" -> subject = unesc(p[1]);
                case "level" -> { try { level = Explainer.Level.valueOf(p[1]); } catch (IllegalArgumentException ignored) { } }
                case "created" -> created = Long.parseLong(p[1]);
                case "previous" -> previous = Long.parseLong(p[1]);
                case "ref" -> { if (p.length >= 8) refs.put(p[1], new Explainer.Source(unesc(p[2]), unesc(p[3]), unesc(p[7]), unesc(p[4]), Long.parseLong(p[5]), Boolean.parseBoolean(p[6]))); }
                case "weak" -> { for (String w : p[1].split(",")) if (!w.isBlank()) weak.add(w); }
                case "followup" -> { if (p.length >= 3) follow.add(new Explainer.FollowUp(unesc(p[1]), unesc(p[2]))); }
                default -> { }
            }
        }
        return Explainer.parse(subject, level, created, text.substring(split + 5), refs, previous, follow).withWeak(weak);
    }

    /** All saved explainers, newest first; unreadable files are skipped. */
    public synchronized List<Explainer.Result> all() {
        List<Explainer.Result> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".txt")).toList()) {
                try { out.add(read(f)); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Reading explainer " + f.getFileName(), ex); }
            }
        } catch (IOException ignored) { }
        out.sort(Comparator.comparingLong(Explainer.Result::created).reversed());
        return out;
    }

    /** The newest explainer of this subject, or null. */
    public Explainer.Result latest(String subject) {
        String k = Explainer.key(subject);
        for (Explainer.Result r : all()) if (r.key().equals(k)) return r;
        return null;
    }

    public synchronized void delete(Explainer.Result r) throws IOException { Files.deleteIfExists(file(r)); }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", ""); }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }
}
