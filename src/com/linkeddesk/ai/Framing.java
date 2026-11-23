package com.linkeddesk.ai;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How outlets framed it (AI-F07): Claude compares how the outlets in one story covered it: what they all report,
 * what each stresses, which facts only some report, and differences in wording. Outlets are sent as [O1], [O2]…;
 * a citation to an outlet that wasn't sent is removed. Claude describes the coverage; it doesn't judge who is right.
 */
public final class Framing {
    private Framing() {}

    public static final int MAX_OUTLETS = 12;
    public static final int MAX_TOKENS = 1100;
    public static final Pattern TAG = Pattern.compile("\\[\\s*O(\\d+)\\s*\\]");
    public static final String[] SECTIONS = {"COMMON", "EMPHASIS", "ONLY SOME", "WORDING"};
    public static final Map<String, String> TITLES = Map.of("COMMON", "What they all report", "EMPHASIS", "What each outlet stresses",
            "ONLY SOME", "Reported by only some", "WORDING", "Differences in wording");

    public record Result(StoryCluster story, Map<String, Article> refs, Map<String, List<String>> sections, List<String> problems, Set<String> cited) {
        public boolean isEmpty() { return sections.values().stream().allMatch(List::isEmpty); }
    }

    /** One article per outlet (the one with the most text), up to {@link #MAX_OUTLETS}, most text first. */
    public static Map<String, Article> outlets(StoryCluster c, Function<Article, String> fullText) {
        Map<String, Article> best = new LinkedHashMap<>();
        Map<String, Integer> size = new HashMap<>();
        for (Article a : c.articlesNewestFirst()) {
            int n = a.title.length() + a.summary.length() + Math.min(1200, fullText.apply(a).length());
            if (n > size.getOrDefault(a.source, -1)) { best.put(a.source, a); size.put(a.source, n); }
        }
        List<Article> l = new ArrayList<>(best.values());
        l.sort(Comparator.comparingInt((Article a) -> size.get(a.source)).reversed());
        Map<String, Article> refs = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(MAX_OUTLETS, l.size()); i++) refs.put("O" + (i + 1), l.get(i));
        return refs;
    }

    public static String prompt(StoryCluster c, Map<String, Article> refs, Function<Article, String> fullText) {
        StringBuilder sb = new StringBuilder("""
                Several outlets covered the same news event. Compare how they covered it, using only the text below \
                (often just a headline and a short summary, sometimes more). Describe the coverage; do not judge which \
                outlet is right, and do not add facts that are not in the text. Cite outlets as [O1], [O2]… after each point.

                Reply in exactly this form, with short bullet points (leave a section empty if there is nothing to say):
                COMMON:
                - what all or most outlets report [O1][O2]
                EMPHASIS:
                - what one outlet puts first or stresses that others don't [O3]
                ONLY SOME:
                - facts, figures or quotes that only some outlets report [O4]
                WORDING:
                - notable differences in words used for the same thing, quoted briefly [O1][O5]

                """);
        sb.append("EVENT: ").append(c.headline).append("\n\n");
        for (var e : refs.entrySet()) {
            Article a = e.getValue();
            String sum = a.summary.length() > 600 ? a.summary.substring(0, 600) + "…" : a.summary;
            String full = fullText.apply(a);
            if (full.length() > 1200) full = full.substring(0, 1200) + "…";
            sb.append("[").append(e.getKey()).append("] ").append(a.source).append("\nHeadline: ").append(a.title).append('\n');
            if (!sum.isBlank()) sb.append("Summary: ").append(sum).append('\n');
            if (!full.isBlank()) sb.append("Text: ").append(full.replace('\n', ' ')).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    public static Result parse(String reply, StoryCluster c, Map<String, Article> refs) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String s : SECTIONS) sections.put(s, new ArrayList<>());
        List<String> problems = new ArrayList<>();
        Set<String> cited = new LinkedHashSet<>(), bad = new TreeSet<>();
        String cur = null;
        for (String raw : reply.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty()) continue;
            String up = l.replace("*", "").replace("#", "").strip().toUpperCase(Locale.ROOT);
            String head = null;
            for (String s : SECTIONS) if (up.startsWith(s + ":") || up.equals(s)) head = s;
            if (head != null) {
                cur = head;
                String rest = l.substring(l.indexOf(':') + 1).replace("*", "").strip();
                if (l.contains(":") && !rest.isEmpty()) l = rest; else continue;
            }
            if (cur == null) continue;
            l = l.replaceFirst("^[-•*]\\s*", "");
            Matcher m = TAG.matcher(l);
            StringBuilder kept = new StringBuilder();
            while (m.find()) {
                String id = "O" + m.group(1);
                if (refs.containsKey(id)) { cited.add(id); m.appendReplacement(kept, Matcher.quoteReplacement("[" + id + "]")); }
                else { bad.add(id); m.appendReplacement(kept, ""); }
            }
            m.appendTail(kept);
            String line = kept.toString().replaceAll("\\s{2,}", " ").strip();
            if (!line.isEmpty()) sections.get(cur).add(line);
        }
        if (!bad.isEmpty()) problems.add("Citations to outlets that weren't sent were removed: " + String.join(", ", bad) + ".");
        return new Result(c, refs, sections, problems, cited);
    }
}
