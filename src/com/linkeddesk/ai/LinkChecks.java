package com.linkeddesk.ai;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude's verdicts on the strongest links (AI-F03), kept in link-checks.tsv by article ids so a verdict stays
 * with the two stories as they grow, and a link is never sent twice.
 */
public final class LinkChecks {
    public enum Verdict { REAL, COINCIDENCE, UNSURE }

    public record Check(Set<String> a, Set<String> b, Verdict verdict, String reason, long time, String model) {}

    public static final int MAX_TOKENS_PER_LINK = 60;
    private final Path file;
    private final List<Check> checks = new ArrayList<>();

    public LinkChecks(Path home) { this.file = home.resolve("link-checks.tsv"); }

    public synchronized LinkChecks load() {
        checks.clear();
        if (!Files.exists(file)) return this;
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] p = l.split("\t", -1);
                if (p.length < 5) continue;
                try {
                    checks.add(new Check(set(p[0]), set(p[1]), Verdict.valueOf(p[2]), p[3], Long.parseLong(p[4]), p.length > 5 ? p[5] : ""));
                } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { }
        return this;
    }

    private static Set<String> set(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.strip());
        return out;
    }

    public synchronized void add(List<Check> more) throws IOException {
        checks.addAll(more);
        StringBuilder sb = new StringBuilder("# Claude's verdicts on links: article ids (story 1)\tarticle ids (story 2)\tverdict\treason\ttime\tmodel\n");
        for (Check c : checks) sb.append(String.join(",", c.a())).append('\t').append(String.join(",", c.b())).append('\t').append(c.verdict())
                .append('\t').append(c.reason().replace('\t', ' ').replace('\n', ' ')).append('\t').append(c.time()).append('\t').append(c.model()).append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized int size() { return checks.size(); }

    /** Claude's verdict on the link between these two stories, or null if it hasn't checked it. */
    public synchronized Check find(StoryCluster x, StoryCluster y) {
        Set<String> ix = new HashSet<>(), iy = new HashSet<>();
        for (Article a : x.articles) ix.add(a.id);
        for (Article a : y.articles) iy.add(a.id);
        Check found = null;
        for (Check c : checks)
            if ((overlap(c.a(), ix) && overlap(c.b(), iy)) || (overlap(c.a(), iy) && overlap(c.b(), ix))) found = c;
        return found;
    }

    private static boolean overlap(Set<String> p, Set<String> q) {
        for (String s : p) if (q.contains(s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ prompt and answer

    public static String prompt(List<StoryLink> links) {
        StringBuilder sb = new StringBuilder("""
                A news app linked these pairs of stories because they share names or words. For each numbered pair decide:
                REAL = the stories are connected (same actors in a connected situation, cause and effect, or one event's \
                consequences);
                COINCIDENCE = they only share a name or word;
                UNSURE = the reporting below isn't enough to tell.
                Use only the reporting given. Answer with one line per pair and nothing else, exactly like:
                L1 | REAL | reason in under 25 words

                """);
        for (int i = 0; i < links.size(); i++) {
            StoryLink l = links.get(i);
            sb.append("L").append(i + 1).append(" (shared: ").append(String.join(", ", l.sharedTerms())).append(")\n")
              .append("  A: ").append(l.from().headline).append('\n').append(indent(ClaudeClient.describe(l.from(), 2, 160)))
              .append("  B: ").append(l.to().headline).append('\n').append(indent(ClaudeClient.describe(l.to(), 2, 160))).append('\n');
        }
        return sb.toString();
    }

    private static String indent(String s) { return s.replaceAll("(?m)^", "    "); }

    private static final Pattern LINE = Pattern.compile("^\\W*L(\\d+)\\W*\\|\\s*(REAL|COINCIDENCE|UNSURE)\\w*\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /** Reads Claude's lines; pairs it didn't answer get no verdict. */
    public static List<Check> parse(String text, List<StoryLink> links, String model, long time) {
        List<Check> out = new ArrayList<>();
        Set<Integer> done = new HashSet<>();
        for (String raw : text.split("\\R")) {
            Matcher m = LINE.matcher(raw.strip());
            if (!m.matches()) continue;
            int i = Integer.parseInt(m.group(1)) - 1;
            if (i < 0 || i >= links.size() || !done.add(i)) continue;
            StoryLink l = links.get(i);
            out.add(new Check(ids(l.from()), ids(l.to()), Verdict.valueOf(m.group(2).toUpperCase(Locale.ROOT)), m.group(3).strip(), time, model));
        }
        return out;
    }

    static Set<String> ids(StoryCluster c) {
        Set<String> s = new LinkedHashSet<>();
        for (Article a : c.articles) { s.add(a.id); if (s.size() >= 30) break; }
        return s;
    }
}
