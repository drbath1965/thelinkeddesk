package com.linkeddesk.ai;

import com.linkeddesk.analysis.Coverage;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explain this subject (AI-F08): the app picks the most useful articles on a subject (separate reports first,
 * spread over days and outlets, opinion kept apart) and Claude pieces them together into a plain-language breakdown:
 * the bottom line, a dated timeline, who's involved, why it matters, what's disputed, what to watch, and the jargon.
 * Articles are sent as [S1], [S2]…; a citation to an article that wasn't sent is removed, and in the factual sections
 * a point with no valid citation is left out, so every fact traces to an article.
 */
public final class Explainer {
    private Explainer() {}

    public enum Level {
        QUICK("Quick", 14, 1000), DETAILED("Detailed", 25, 2400),
        /** One news article pieced together from linked stories (AI-F10). */
        ARTICLE("Combined article", 30, 2800);
        public final String label; public final int maxArticles, maxTokens;
        Level(String label, int maxArticles, int maxTokens) { this.label = label; this.maxArticles = maxArticles; this.maxTokens = maxTokens; }
        @Override public String toString() { return label; }
    }

    public static final Pattern TAG = Pattern.compile("\\[\\s*S(\\d+)\\s*\\]");
    public static final String[] SECTIONS = {"HEADLINE", "SUMMARY", "ARTICLE", "BOTTOM LINE", "CHANGED", "TIMELINE", "WHO", "WHY IT MATTERS", "DISPUTED", "WATCH", "TERMS"};
    public static final Map<String, String> TITLES = Map.ofEntries(Map.entry("BOTTOM LINE", "The bottom line"), Map.entry("CHANGED", "What's changed since last time"),
            Map.entry("TIMELINE", "What happened"), Map.entry("WHO", "Who's involved"), Map.entry("WHY IT MATTERS", "Why it matters"),
            Map.entry("DISPUTED", "What's disputed or still unknown"), Map.entry("WATCH", "What to watch next"), Map.entry("TERMS", "Terms explained"),
            Map.entry("HEADLINE", "Headline"), Map.entry("SUMMARY", "In brief"), Map.entry("ARTICLE", "The article"));
    /** Sections where every point must cite an article. */
    public static final Set<String> FACTUAL = Set.of("BOTTOM LINE", "CHANGED", "TIMELINE", "WHO", "DISPUTED", "SUMMARY", "ARTICLE");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());

    /** An article as the explainer keeps it (enough to show and link it later). */
    public record Source(String id, String url, String title, String source, long published, boolean opinion) {
        public static Source of(Article a) { return new Source(a.id, a.url, a.title, a.source, a.published, Coverage.opinion(a)); }
        public String day() { return DAY.format(Instant.ofEpochMilli(published)); }
    }

    public record FollowUp(String label, String text) {}

    public record Result(String subject, Level level, long created, Map<String, Source> refs, Map<String, List<String>> sections,
                         List<String> problems, Set<String> cited, String raw, long previousCreated, List<FollowUp> followUps, Set<String> weak) {
        public Result(String subject, Level level, long created, Map<String, Source> refs, Map<String, List<String>> sections,
                      List<String> problems, Set<String> cited, String raw, long previousCreated, List<FollowUp> followUps) {
            this(subject, level, created, refs, sections, problems, cited, raw, previousCreated, followUps, Set.of());
        }
        public String key() { return Explainer.key(subject); }
        /** Points whose citations were checked ("SECTION:i"), or none if not checked. */
        public Result withWeak(Set<String> w) {
            return new Result(subject, level, created, refs, sections, problems, cited, raw, previousCreated, followUps, Set.copyOf(w));
        }
        /** Points that cite sources (the ones the source check looks at). */
        public int citedPoints() {
            int n = 0;
            for (var e : sections.entrySet()) if (!e.getKey().equals("TERMS")) for (String l : e.getValue()) if (TAG.matcher(l).find()) n++;
            return n;
        }
        /** Words in the explainer (sections only, citations left out). */
        public int words() {
            int n = 0;
            for (List<String> l : sections.values()) for (String x : l) { String t = TAG.matcher(x).replaceAll(" ").strip(); if (!t.isEmpty()) n += t.split("\\s+").length; }
            return n;
        }
        /** Minutes to read at about 230 words a minute (at least 1). */
        public int readingMinutes() { return Math.max(1, (int) Math.ceil(words() / 230.0)); }
        public boolean isEmpty() { return sections.values().stream().allMatch(List::isEmpty); }
        public int outlets() { Set<String> s = new HashSet<>(); for (Source r : refs.values()) s.add(r.source()); return s.size(); }
        public int days() { Set<String> s = new HashSet<>(); for (Source r : refs.values()) s.add(r.day()); return s.size(); }
        public Result withFollowUp(FollowUp f) {
            List<FollowUp> l = new ArrayList<>(followUps); l.add(f);
            return new Result(subject, level, created, refs, sections, problems, cited, raw, previousCreated, List.copyOf(l), weak);
        }
    }

    /** "Strait of Hormuz" and "strait of  hormuz" are the same explainer. */
    public static String key(String subject) {
        String s = Normalizer.normalize(subject == null ? "" : subject, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "subject" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    /**
     * Picks up to {@code max} articles: the best report from each story first (the most covered stories first), then
     * more outlets per story, never two copies of one wire report; opinion only if there's room (at most 3).
     * Returned oldest first, numbered S1…
     */
    public static Map<String, Article> select(List<StoryCluster> stories, int max, Function<Article, String> fullText) {
        List<StoryCluster> order = new ArrayList<>(stories);
        order.sort(Comparator.comparingInt(StoryCluster::independentCount).reversed().thenComparing(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed()));
        Set<Article> chosen = new LinkedHashSet<>();
        Set<String> groups = new HashSet<>();
        Map<StoryCluster, Set<String>> outletsUsed = new HashMap<>();
        Function<Article, Integer> size = a -> a.title.length() + a.summary.length() + Math.min(1500, fullText.apply(a).length());
        for (int round = 0; round < 4 && chosen.size() < max; round++) {
            for (StoryCluster c : order) {
                if (chosen.size() >= max) break;
                Map<Article, String> groupOf = new HashMap<>();
                if (c.copies != null) for (Coverage.Copies cp : c.copies) for (Article a : cp.articles()) groupOf.put(a, c.id + "/" + cp.label() + "/" + cp.articles().get(0).id);
                Set<String> used = outletsUsed.computeIfAbsent(c, k -> new HashSet<>());
                Article best = null;
                for (Article a : c.articles) {
                    if (chosen.contains(a) || used.contains(a.source) || Coverage.opinion(a)) continue;
                    String g = groupOf.get(a);
                    if (g != null && groups.contains(g)) continue;
                    if (best == null || size.apply(a) > size.apply(best)) best = a;
                }
                if (best == null) continue;
                chosen.add(best);
                used.add(best.source);
                String g = groupOf.get(best);
                if (g != null) groups.add(g);
            }
        }
        int opinions = 0;
        for (StoryCluster c : order) for (Article a : c.articles) {
            if (chosen.size() >= max || opinions >= 3) break;
            if (Coverage.opinion(a) && chosen.add(a)) opinions++;
        }
        List<Article> l = new ArrayList<>(chosen);
        l.sort(Comparator.comparingLong(a -> a.published));
        Map<String, Article> refs = new LinkedHashMap<>();
        for (int i = 0; i < l.size(); i++) refs.put("S" + (i + 1), l.get(i));
        return refs;
    }

    public static String prompt(String subject, Map<String, Article> refs, Function<Article, String> fullText, Level level, Result previous) {
        return prompt(subject, refs, fullText, level, previous, List.of());
    }

    /** {@code interests}: the reader's topics and watches, so WHY IT MATTERS can say how it bears on them. */
    public static String prompt(String subject, Map<String, Article> refs, Function<Article, String> fullText, Level level, Result previous, List<String> interests) {
        if (level == Level.ARTICLE) return articlePrompt(subject, refs, fullText, previous);
        boolean quick = level == Level.QUICK;
        StringBuilder sb = new StringBuilder();
        sb.append("Explain this subject to a busy, intelligent reader who hasn't followed it: ").append(subject).append("\n\n")
          .append("Use ONLY the news articles below. Write plainly: short sentences, no jargon without explaining it. ")
          .append("Cite the articles each point comes from as [S1], [S2]… right after the point. Every point in BOTTOM LINE, ")
          .append(previous != null ? "CHANGED, " : "").append("TIMELINE, WHO and DISPUTED must cite at least one article. ")
          .append("Keep what the reports say apart from your own reading: if a point in WHY IT MATTERS or WATCH is your own reading, ")
          .append("end it with \"(Claude's reading)\". Pieces marked OPINION are commentary: attribute them (\"a columnist argues…\"), never state them as fact. ")
          .append("Do not take sides; where sources disagree, say who says what.\n");
        if (interests != null && !interests.isEmpty())
            sb.append("The reader follows: ").append(String.join("; ", interests.subList(0, Math.min(12, interests.size()))))
              .append(". In WHY IT MATTERS, say how this bears on those where the articles support it; don't force a connection.\n");
        sb.append("\n")
          .append("Reply in exactly this form").append(quick ? ", keeping it to one screen (at most 250 words)" : " (at most 650 words, so it reads in under 3 minutes)").append(":\n")
          .append("BOTTOM LINE:\n- 2-3 sentences on what this is and where it stands now [S1]\n");
        if (previous != null) sb.append("CHANGED:\n- what is new or different since the earlier explainer below (if nothing, say so) [S4]\n");
        sb.append("TIMELINE:\n- Mon D: what happened, oldest first").append(quick ? " (up to 5 entries)" : "").append(" [S2]\n")
          .append("WHO:\n- name: who they are and what they want or did [S3]\n")
          .append("WHY IT MATTERS:\n- for whom and why [S1]\n")
          .append("DISPUTED:\n- what sources disagree on or what isn't known yet [S2][S5]\n")
          .append("WATCH:\n- dates, decisions or signals coming up [S6]\n")
          .append("TERMS:\n- TERM: plain-language meaning\n\n");
        if (previous != null) {
            sb.append("EARLIER EXPLAINER (").append(DAY.format(Instant.ofEpochMilli(previous.created()))).append("), for CHANGED only; its citations refer to other articles:\n");
            for (String s : List.of("BOTTOM LINE", "TIMELINE"))
                for (String l : previous.sections().getOrDefault(s, List.of())) sb.append("- ").append(TAG.matcher(l).replaceAll("").strip()).append('\n');
            sb.append('\n');
        }
        articles(sb, refs, fullText, quick ? 400 : 600, quick ? 0 : 900, previous, "EXPLAINER");
        return sb.toString();
    }

    /**
     * Combined article (AI-F10): one news article pieced together from a story and the stories linked to it, every
     * paragraph citing its articles. {@code previous}: the last article on the same story, to lead with what's new.
     */
    public static String articlePrompt(String subject, Map<String, Article> refs, Function<Article, String> fullText, Result previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write ONE news article that pieces together the linked stories below into a single clear account for a busy, intelligent reader. ")
          .append("The main story is: ").append(subject).append("\n\n")
          .append("Use ONLY the articles below. Show how the stories connect: what led to what, who or what links them, and where accounts differ. ")
          .append("Write plainly, in short paragraphs, in the order that makes the whole picture easiest to follow (usually most important first, then how it came about). ")
          .append("Cite the articles each fact comes from as [S1], [S2]… at the end of the sentence; every paragraph must cite at least one article. ")
          .append("Attribute claims (\"Ukraine says…\", \"according to Reuters…\"); pieces marked OPINION are commentary: attribute them, never state them as fact. ")
          .append("If you add your own reading, end that sentence with \"(Claude's reading)\". Do not take sides. At most 700 words.\n\n")
          .append("Reply in exactly this form:\nHEADLINE: one line, no citation\nSUMMARY: two sentences on the whole picture [S1][S3]\nARTICLE:\n")
          .append("first paragraph [S1]\n\nnext paragraph [S2][S4]\n\n…\n\n");
        if (previous != null) {
            sb.append("An earlier version of this article (").append(DAY.format(Instant.ofEpochMilli(previous.created())))
              .append(") said, in brief (its citations refer to other articles):\n");
            for (String l : previous.sections().getOrDefault("SUMMARY", List.of())) sb.append("- ").append(TAG.matcher(l).replaceAll("").strip()).append('\n');
            sb.append("Make the second paragraph start with \"Since ").append(DAY.format(Instant.ofEpochMilli(previous.created())))
              .append("\" and say what is new.\n\n");
        }
        articles(sb, refs, fullText, 600, 900, previous, "ARTICLE");
        return sb.toString();
    }

    private static void articles(StringBuilder sb, Map<String, Article> refs, Function<Article, String> fullText, int sumMax, int fullMax, Result previous, String what) {
        sb.append("ARTICLES (oldest first):\n\n");
        for (var e : refs.entrySet()) {
            Article a = e.getValue();
            sb.append('[').append(e.getKey()).append("] ").append(a.source).append(" · ").append(DAY.format(Instant.ofEpochMilli(a.published)))
              .append(Coverage.opinion(a) ? " · OPINION" : "");
            if (previous != null && a.fetched > previous.created()) sb.append(" · NEW SINCE THE EARLIER ").append(what);
            sb.append("\nHeadline: ").append(a.title).append('\n');
            String sum = cut(a.summary, sumMax);
            if (!sum.isBlank()) sb.append("Summary: ").append(sum).append('\n');
            String full = fullMax == 0 ? "" : cut(fullText.apply(a), fullMax);
            if (!full.isBlank() && !full.startsWith(sum.replace("…", ""))) sb.append("Text: ").append(full.replace('\n', ' ')).append('\n');
            sb.append('\n');
        }
    }

    /** The article's headline (or the subject if Claude gave none). */
    public static String headline(Result r) {
        List<String> h = r.sections().getOrDefault("HEADLINE", List.of());
        return h.isEmpty() ? r.subject() : TAG.matcher(h.get(0)).replaceAll("").strip();
    }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s; }

    public static Result parse(String subject, Level level, long created, String reply, Map<String, Source> refs, long previousCreated, List<FollowUp> followUps) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String s : SECTIONS) sections.put(s, new ArrayList<>());
        List<String> problems = new ArrayList<>();
        Set<String> cited = new LinkedHashSet<>(), bad = new TreeSet<>(Comparator.comparingInt((String x) -> Integer.parseInt(x.substring(1))));
        int uncited = 0;
        String cur = null;
        for (String raw : reply.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty()) continue;
            String up = l.replace("*", "").replace("#", "").strip().toUpperCase(Locale.ROOT);
            String head = null;
            for (String s : SECTIONS) if (up.startsWith(s + ":") || up.equals(s)) head = s;
            if (head != null) {
                cur = head;
                int c = l.indexOf(':');
                String rest = c < 0 ? "" : l.substring(c + 1).replace("*", "").strip();
                if (rest.isEmpty()) continue;
                l = rest;
            }
            if (cur == null) continue;
            l = l.replaceFirst("^(?:[-•*]|\\d+[.)])\\s*", "");
            Matcher m = TAG.matcher(l);
            StringBuilder kept = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                String id = "S" + m.group(1);
                if (refs.containsKey(id)) { cited.add(id); any = true; m.appendReplacement(kept, Matcher.quoteReplacement("[" + id + "]")); }
                else { bad.add(id); m.appendReplacement(kept, ""); }
            }
            m.appendTail(kept);
            String line = kept.toString().replaceAll("\\s{2,}", " ").replaceAll("\\s+([.,;])", "$1").strip();
            if (line.isEmpty() || line.matches("[-–—.]+")) continue;
            if (FACTUAL.contains(cur) && !any) { uncited++; continue; }
            sections.get(cur).add(line);
        }
        if (!bad.isEmpty()) problems.add("Citations to articles Claude wasn't given were removed: " + String.join(", ", bad) + ".");
        if (uncited > 0) problems.add(uncited + (uncited == 1 ? " point without a source was" : " points without a source were") + " left out.");
        return new Result(subject, level, created, refs, sections, problems, cited, reply, previousCreated, followUps == null ? List.of() : List.copyOf(followUps));
    }

    public static Map<String, Source> sources(Map<String, Article> refs) {
        Map<String, Source> m = new LinkedHashMap<>();
        refs.forEach((k, a) -> m.put(k, Source.of(a)));
        return m;
    }

    // ------------------------------------------------------------------ follow-ups

    /** "Explain it more simply": the same explainer, for someone new to the subject. */
    public static String simplerPrompt(Result r) {
        return "Rewrite this explanation of \"" + r.subject() + "\" for someone completely new to it: about 150 words, everyday words, "
                + "one short paragraph on what it is and why people care, then three bullet points. Keep the [S#] citations on the facts "
                + "and add no facts that aren't in it.\n\n" + body(r);
    }

    /** "Go deeper on this point": what the articles say about one point, in more detail. */
    public static String deeperPrompt(Result r, String point, Map<String, Article> refs, Function<Article, String> fullText) {
        StringBuilder sb = new StringBuilder("A reader wants more on this point from an explanation of \"").append(r.subject()).append("\":\n\n  ")
                .append(point).append("\n\nUsing ONLY the articles below, explain it in more depth in 120-250 words: the details, the background the ")
                .append("articles give, and what isn't known. Cite the articles as [S#]. If the articles don't say more, say so.\n\nARTICLES:\n\n");
        for (var e : refs.entrySet()) {
            Article a = e.getValue();
            sb.append('[').append(e.getKey()).append("] ").append(a.source).append(" · ").append(DAY.format(Instant.ofEpochMilli(a.published)))
              .append("\nHeadline: ").append(a.title).append("\nSummary: ").append(cut(a.summary, 700)).append('\n');
            String full = cut(fullText.apply(a), 1500);
            if (!full.isBlank()) sb.append("Text: ").append(full.replace('\n', ' ')).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ source check

    private static final Set<String> MONTHS = Set.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "says", "said", "claude", "reading", "reported", "reports");

    /** The content words of a text: lower case, 4+ letters (or any number), not common words, simply stemmed. */
    static Set<String> content(String text) {
        Set<String> out = new HashSet<>();
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String w : t.split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty()) continue;
            boolean num = w.chars().allMatch(Character::isDigit);
            if (!num && (w.length() < 4 || com.linkeddesk.analysis.Text.STOP.contains(w) || MONTHS.contains(w))) continue;
            if (num && w.length() < 2) continue;
            out.add(stem(w));
        }
        return out;
    }

    private static String stem(String w) {
        if (w.length() > 6 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 5 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 5 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    /**
     * Source check: for each point that cites articles (terms aside), does it share enough key words with the
     * articles it cites? A point that doesn't is returned as "SECTION:i" so it can be marked "check source".
     * It catches a citation pointing at the wrong article, not subtle misreadings, so the spot check still matters.
     */
    public static Set<String> weakPoints(Result r, Function<String, String> textOfRef) {
        Set<String> weak = new LinkedHashSet<>();
        for (var e : r.sections().entrySet()) {
            if (e.getKey().equals("TERMS")) continue;
            List<String> lines = e.getValue();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                Matcher m = TAG.matcher(l);
                StringBuilder src = new StringBuilder();
                boolean any = false;
                while (m.find()) { any = true; src.append(' ').append(textOfRef.apply("S" + m.group(1))); }
                if (!any) continue;
                Set<String> point = content(TAG.matcher(l).replaceAll(" ").replace("(Claude's reading)", " "));
                if (point.isEmpty()) continue;
                Set<String> have = content(src.toString());
                int shared = 0;
                for (String w : point) if (have.contains(w)) shared++;
                boolean ok = point.size() <= 3 ? shared >= 1 : shared >= 2 && shared >= Math.ceil(point.size() * 0.25);
                if (!ok) weak.add(e.getKey() + ":" + i);
            }
        }
        return weak;
    }

    /** Removes citations to articles that weren't sent from a follow-up answer. */
    public static String clean(String text, Map<String, Source> refs) {
        Matcher m = TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, refs.containsKey("S" + m.group(1)) ? Matcher.quoteReplacement("[S" + m.group(1) + "]") : "");
        return m.appendTail(sb).toString().replaceAll("[ \\t]{2,}", " ").replaceAll("[ \\t]+([.,;:])", "$1").strip();
    }

    /** Replaces each [S#] in an (already escaped) line with {@code anchor} of its article. */
    public static String render(String line, Map<String, Source> refs, Function<Source, String> anchor) {
        Matcher m = TAG.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Source s = refs.get("S" + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(s == null ? "" : anchor.apply(s)));
        }
        return m.appendTail(sb).toString();
    }

    /** Short date for a citation, e.g. "Nov 3". */
    public static String shortDay(Source s) { return DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(s.published())); }

    static String body(Result r) {
        StringBuilder sb = new StringBuilder();
        for (String s : SECTIONS) {
            List<String> l = r.sections().get(s);
            if (l == null || l.isEmpty()) continue;
            sb.append(TITLES.get(s)).append(":\n");
            for (String x : l) sb.append("- ").append(x).append('\n');
        }
        return sb.toString();
    }
}
