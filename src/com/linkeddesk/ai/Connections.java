package com.linkeddesk.ai;

import com.linkeddesk.analysis.Countries;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find connections with Claude (AI-F09). The automatic linker only links stories that share distinctive words.
 * This gathers up to 40 candidate stories for one story (sharing people, places or terms, near misses, and recent
 * stories on other desks), and Claude says which are really connected and how: cause and effect, a follow-up or
 * earlier chapter, the same people, the same place, background, conflicting accounts, or a market or policy impact.
 * Candidates are sent as [C1]…; Claude can only connect stories it was sent.
 */
public final class Connections {
    private Connections() {}

    public static final int MAX_CANDIDATES = 40, MAX_TOKENS = 900;

    public enum Kind {
        CAUSE("Cause and effect"), FOLLOWUP("Follow-up or earlier chapter"), ACTORS("Same people or organizations"), PLACE("Same place"),
        BACKGROUND("Background"), CONFLICT("Conflicting accounts"), IMPACT("Market or policy impact");
        public final String label;
        Kind(String label) { this.label = label; }

        public static Kind parse(String s) {
            String k = s == null ? "" : s.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            return switch (k) {
                case "CAUSE", "CAUSEANDEFFECT", "CAUSEEFFECT", "EFFECT" -> CAUSE;
                case "FOLLOWUP", "EARLIERCHAPTER", "CHAPTER", "SEQUEL" -> FOLLOWUP;
                case "ACTORS", "SAMEACTORS", "SAMEPEOPLE", "PEOPLE", "ORGANIZATIONS", "SAMEPEOPLEORORGANIZATIONS" -> ACTORS;
                case "PLACE", "SAMEPLACE", "LOCATION" -> PLACE;
                case "BACKGROUND", "CONTEXT" -> BACKGROUND;
                case "CONFLICT", "CONFLICTING", "CONFLICTINGACCOUNTS", "CONTRADICTS" -> CONFLICT;
                case "IMPACT", "MARKET", "POLICY", "MARKETIMPACT", "POLICYIMPACT", "MARKETORPOLICYIMPACT" -> IMPACT;
                default -> null;
            };
        }
    }

    public enum Confidence { HIGH, MEDIUM, LOW;
        static Confidence parse(String s) {
            String k = s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
            return k.startsWith("H") ? HIGH : k.startsWith("L") ? LOW : MEDIUM;
        }
        public String label() { return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT); }
    }

    /** A story Claude is shown, and why it was picked. */
    public record Candidate(String ref, StoryCluster story, String why) {}

    /** A connection Claude found. */
    public record Found(Candidate candidate, Kind kind, Confidence confidence, String reason) {}

    public record Result(StoryCluster target, List<Candidate> candidates, List<Found> found, List<String> problems) {}

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());

    /** Capitalised names and countries in a story's headline and summaries (folded, for matching). */
    public static Set<String> names(StoryCluster c) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder(c.headline == null ? "" : c.headline);
        int n = 0;
        for (Article a : c.articles) { text.append(". ").append(a.title).append(". ").append(a.summary); if (++n >= 6) break; }
        for (var k : Countries.mentions(text.toString()).keySet()) out.add(fold(k.name()));
        if (c.keyTerms != null) for (String t : c.keyTerms) if (!t.isEmpty() && Character.isUpperCase(t.charAt(0))) out.add(fold(t));
        Matcher m = NAME.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 4 && !com.linkeddesk.analysis.Text.STOP.contains(w.toLowerCase(Locale.ROOT)) && !COMMON.contains(w)) out.add(fold(w));
        }
        return out;
    }

    private static final Pattern NAME = Pattern.compile("\\b\\p{Lu}[\\p{L}'’-]+(?:\\s+\\p{Lu}[\\p{L}'’-]+)*");
    private static final Set<String> COMMON = Set.of("The", "This", "That", "What", "When", "Where", "Why", "How", "After", "Before", "Over", "Under",
            "News", "Live", "Update", "Updates", "Breaking", "Opinion", "Analysis", "Watch", "Video", "Photos", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April", "June", "July", "August", "September",
            "October", "November", "December", "Reuters", "Associated Press", "First", "Last", "Says", "New");

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('’', '\'').strip();
    }

    /**
     * Up to {@code max} candidate stories for {@code target} from {@code pool}: stories sharing names or places
     * (most shared first, near misses of the linker get a bonus), then recent stories on other desks so Claude can
     * spot causes and effects that share no words. Stories already linked to it, in its storyline, or that
     * {@code skip} rules out (you rejected or accepted the pair before) are left out.
     */
    public static List<Candidate> candidates(StoryCluster target, Collection<StoryCluster> pool, int max, BiPredicate<StoryCluster, StoryCluster> skip, long now) {
        Set<StoryCluster> linked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (StoryLink l : target.links) linked.add(l.to());
        Set<String> mine = names(target);
        Set<String> myIds = new HashSet<>();
        for (Article a : target.articles) myIds.add(a.id);
        record Scored(StoryCluster c, double score, String why) {}
        List<Scored> named = new ArrayList<>(), recentOther = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StoryCluster c : pool) {
            if (c == target || linked.contains(c) || c.continues == target || target.continues == c) continue;
            boolean overlap = false;
            for (Article a : c.articles) if (myIds.contains(a.id)) { overlap = true; break; }
            if (overlap || !seen.add(c.id) || (skip != null && skip.test(target, c))) continue;
            Set<String> theirs = names(c);
            List<String> shared = new ArrayList<>();
            for (String n : mine) if (theirs.contains(n)) shared.add(n);
            long days = Math.abs(target.lastSeen - c.lastSeen) / 86_400_000L;
            if (!shared.isEmpty()) {
                double s = shared.size() * 2.0 + Math.min(4, c.independentCount()) * 0.25 - Math.min(30, days) * 0.05;
                named.add(new Scored(c, s, "shares " + String.join(", ", shared.subList(0, Math.min(3, shared.size())))));
            } else if (c.category != target.category && now - c.lastSeen < 3 * 86_400_000L) {
                recentOther.add(new Scored(c, c.independentCount(), "recent, " + c.category.label() + " desk"));
            }
        }
        named.sort(Comparator.comparingDouble(Scored::score).reversed());
        recentOther.sort(Comparator.comparingDouble(Scored::score).reversed());
        int otherSlots = Math.min(recentOther.size(), Math.max(max / 4, max - named.size()));
        List<Scored> pick = new ArrayList<>(named.subList(0, Math.min(named.size(), max - otherSlots)));
        pick.addAll(recentOther.subList(0, Math.min(otherSlots, max - pick.size())));
        pick.sort(Comparator.comparingLong(s -> s.c().lastSeen));
        List<Candidate> out = new ArrayList<>();
        for (Scored s : pick) out.add(new Candidate("C" + (out.size() + 1), s.c(), s.why()));
        return out;
    }

    public static String prompt(StoryCluster target, List<Candidate> cands) {
        StringBuilder sb = new StringBuilder("""
                A news reader is looking at the TARGET story below. Which of the CANDIDATE stories are genuinely connected to it, \
                beyond merely being on a broad subject (e.g. both about "the economy" or both about "Russia")? Look especially for \
                connections that don't share the same words: one event causing or responding to another, a follow-up or an earlier \
                chapter of the same affair, the same people or organizations acting in both, the same place, useful background, \
                reports that conflict with each other, or a market or policy impact. Use only what the text below says.

                Reply with one line per connected candidate, most important first, at most 10, in exactly this form:
                C# | KIND | CONFIDENCE | one sentence naming the concrete connection
                KIND is one of: CAUSE, FOLLOWUP, ACTORS, PLACE, BACKGROUND, CONFLICT, IMPACT
                CONFIDENCE is HIGH or MEDIUM (leave out anything weaker).
                If none are connected, reply NONE.

                """);
        sb.append("TARGET: ").append(target.headline).append(" (").append(target.category.label()).append(", ")
          .append(DAY.format(Instant.ofEpochMilli(target.lastSeen))).append(")\n");
        int n = 0;
        for (Article a : target.articlesNewestFirst()) {
            if (a.summary.isBlank()) continue;
            sb.append("  - ").append(a.source).append(": ").append(cut(a.summary, 300)).append('\n');
            if (++n >= 3) break;
        }
        sb.append("\nCANDIDATES:\n");
        for (Candidate c : cands) {
            StoryCluster s = c.story();
            String sum = "";
            for (Article a : s.articlesNewestFirst()) if (!a.summary.isBlank()) { sum = a.summary; break; }
            sb.append('[').append(c.ref()).append("] ").append(s.headline).append(" (").append(s.category.label()).append(", ")
              .append(DAY.format(Instant.ofEpochMilli(s.lastSeen))).append(", ").append(s.sourceCount()).append(s.sourceCount() == 1 ? " outlet)" : " outlets)");
            if (!sum.isBlank()) sb.append("\n    ").append(cut(sum, 220));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    private static final Pattern LINE = Pattern.compile("^\\W*\\[?\\s*C(\\d+)\\s*\\]?\\s*[|:–—-]\\s*([A-Za-z ]+?)\\s*[|:–—-]\\s*([A-Za-z]+)\\s*[|:–—-]\\s*(.+)$");

    public static Result parse(String reply, StoryCluster target, List<Candidate> cands) {
        Map<String, Candidate> byRef = new HashMap<>();
        for (Candidate c : cands) byRef.put(c.ref(), c);
        List<Found> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> bad = new TreeSet<>(), used = new HashSet<>();
        int unread = 0, low = 0;
        for (String raw : reply.split("\\R")) {
            String l = raw.strip().replace("*", "");
            if (l.isEmpty() || l.equalsIgnoreCase("NONE")) continue;
            Matcher m = LINE.matcher(l);
            if (!m.find()) { if (l.matches("(?i).*\\bC\\d+\\b.*")) unread++; continue; }
            String ref = "C" + m.group(1);
            Candidate c = byRef.get(ref);
            if (c == null) { bad.add(ref); continue; }
            Kind k = Kind.parse(m.group(2));
            if (k == null) { unread++; continue; }
            Confidence conf = Confidence.parse(m.group(3));
            if (conf == Confidence.LOW) { low++; continue; }
            if (!used.add(ref)) continue;
            String reason = m.group(4).strip().replaceAll("\\s*\\[?C\\d+\\]?", " ").replaceAll("\\s{2,}", " ").strip();
            found.add(new Found(c, k, conf, reason));
            if (found.size() >= 10) break;
        }
        if (!bad.isEmpty()) problems.add("Connections to stories Claude wasn't shown were dropped: " + String.join(", ", bad) + ".");
        if (unread > 0) problems.add(unread + (unread == 1 ? " line" : " lines") + " couldn't be read and were skipped.");
        if (low > 0) problems.add(low + " low-confidence " + (low == 1 ? "suggestion was" : "suggestions were") + " left out.");
        return new Result(target, cands, found, problems);
    }
}
