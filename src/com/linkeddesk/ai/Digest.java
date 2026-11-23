package com.linkeddesk.ai;

import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The daily digest (AI-F02): Claude reads the briefing's top stories and new links and writes how the day's
 * events connect, keeping what the reports say (REPORTED) apart from its own reading (INFERENCE), citing stories
 * as [S1], [S2]. Saved per day in digests/YYYY-MM-DD.txt.
 */
public record Digest(LocalDate day, long made, List<String> reported, List<String> inference, List<String> watch,
                     Map<String, Set<String>> refs, List<String> problems) {

    public static final int MAX_TOKENS = 900;
    static final int MAX_STORIES = 24;

    /** The stories sent: the briefing's top stories per desk, then the stories of new cross-desk links. */
    public static Map<String, StoryCluster> stories(Briefing b) {
        Set<StoryCluster> pick = new LinkedHashSet<>();
        for (var list : b.desks().values()) for (var r : list) pick.add(r.story());
        for (StoryLink l : b.newLinks()) { pick.add(l.from()); pick.add(l.to()); }
        List<StoryCluster> l = new ArrayList<>(pick);
        if (l.size() > MAX_STORIES) l = l.subList(0, MAX_STORIES);
        return Cite.number(l);
    }

    public static String prompt(Briefing b, Map<String, StoryCluster> refs) {
        StringBuilder sb = new StringBuilder("""
                You write the morning digest for a world-news reader. Below are today's top news stories (last 24 hours), \
                each with an id like [S3], and links between stories found by the app.

                Write how today's events connect: up to 6 short chains of cause and effect across the stories.
                Rules:
                - Use only the reporting below. End every line with the ids of the stories it rests on, like [S2][S5].
                - Under REPORTED put only what the reports say. Under INFERENCE put your own connections and readings.
                - Under WATCH put up to 3 things to watch next.
                - Plain text, one point per line starting with "- ", exactly these three headings and nothing else:
                REPORTED:
                INFERENCE:
                WATCH:

                STORIES
                """);
        for (var e : refs.entrySet()) {
            StoryCluster c = e.getValue();
            sb.append('[').append(e.getKey()).append("] ").append(c.category.label()).append(" desk, ")
              .append(c.sourceCount()).append(c.sourceCount() == 1 ? " outlet" : " outlets").append(": ").append(c.headline).append('\n')
              .append(ClaudeClient.describe(c, 3, 200));
        }
        if (!b.newLinks().isEmpty()) {
            sb.append("\nLINKS FOUND BY THE APP (shared words, not proof of a connection)\n");
            Map<StoryCluster, String> id = new LinkedHashMap<>();
            refs.forEach((k, v) -> id.put(v, k));
            for (StoryLink l : b.newLinks()) {
                String a = id.get(l.from()), c = id.get(l.to());
                if (a != null && c != null) sb.append(a).append(" - ").append(c).append(": ").append(String.join(", ", l.sharedTerms())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Reads Claude's answer into its three parts, keeping only citations to stories that were sent. */
    public static Digest parse(String text, Map<String, Set<String>> refs, long made) {
        List<String> rep = new ArrayList<>(), inf = new ArrayList<>(), watch = new ArrayList<>(), problems = new ArrayList<>();
        List<String> cur = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            String up = line.toUpperCase(java.util.Locale.ROOT).replace("*", "").replace("#", "").trim();
            if (up.startsWith("REPORTED")) { cur = rep; continue; }
            if (up.startsWith("INFERENCE")) { cur = inf; continue; }
            if (up.startsWith("WATCH")) { cur = watch; continue; }
            if (cur == null || line.isEmpty()) continue;
            line = line.replaceFirst("^[-•*]\\s*", "");
            line = Cite.clean(line, refs.keySet(), problems);
            if (!line.isEmpty()) cur.add(line);
        }
        if (rep.isEmpty() && inf.isEmpty()) problems.add("Claude's answer didn't have the expected REPORTED / INFERENCE parts.");
        LocalDate day = Instant.ofEpochMilli(made).atZone(ZoneId.systemDefault()).toLocalDate();
        return new Digest(day, made, rep, inf, watch, refs, problems);
    }

    public boolean isEmpty() { return reported.isEmpty() && inference.isEmpty(); }

    // ------------------------------------------------------------------ file

    public static Path file(Path home, LocalDate day) { return home.resolve("digests").resolve(day + ".txt"); }

    public void save(Path home) throws IOException {
        StringBuilder sb = new StringBuilder("# The Linked Desk daily digest, written by Claude\n# made\t").append(made).append('\n');
        for (var e : refs.entrySet()) sb.append("# ").append(e.getKey()).append('\t').append(String.join(",", e.getValue())).append('\n');
        sb.append("REPORTED:\n");
        for (String s : reported) sb.append("- ").append(s).append('\n');
        sb.append("INFERENCE:\n");
        for (String s : inference) sb.append("- ").append(s).append('\n');
        sb.append("WATCH:\n");
        for (String s : watch) sb.append("- ").append(s).append('\n');
        Path f = file(home, day);
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Today's (or another day's) saved digest, or null. */
    public static Digest load(Path home, LocalDate day) throws IOException {
        Path f = file(home, day);
        if (!Files.exists(f)) return null;
        Map<String, Set<String>> refs = new LinkedHashMap<>();
        long made = 0;
        StringBuilder body = new StringBuilder();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.startsWith("# made\t")) { try { made = Long.parseLong(l.substring(7).trim()); } catch (NumberFormatException ignored) { } continue; }
            if (l.startsWith("# S")) {
                String[] p = l.substring(2).split("\t");
                if (p.length == 2) refs.put(p[0], new LinkedHashSet<>(List.of(p[1].split(","))));
                continue;
            }
            if (l.startsWith("#")) continue;
            body.append(l).append('\n');
        }
        Digest d = parse(body.toString(), refs, made == 0 ? Files.getLastModifiedTime(f).toMillis() : made);
        return new Digest(day, d.made(), d.reported(), d.inference(), d.watch(), refs, d.problems());
    }
}
