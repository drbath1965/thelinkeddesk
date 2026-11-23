package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import java.util.*;
import java.util.function.Predicate;

/**
 * Suggested topics (FET-F09): subjects worth following as a Topic, each with the reason it's suggested:
 * what you read and star, what is rising in the news this week, watches that would get more coverage as a topic,
 * and a starter set by desk when you have few topics.
 */
public final class TopicSuggester {
    private TopicSuggester() {}

    public record Suggestion(String name, String query, Category desk, String reason, int stories, String kind) {}

    /** Starter topics by desk, for when you have few topics of your own. */
    static final String[][] STARTERS = {
            {"DEFENSE", "Redstone Arsenal and Huntsville", "\"Redstone Arsenal\" OR \"Marshall Space Flight Center\" OR \"Missile Defense Agency\""},
            {"DEFENSE", "Hypersonic weapons", "hypersonic OR \"glide vehicle\""},
            {"DEFENSE", "Golden Dome missile defense", "\"Golden Dome\" OR \"missile defense\""},
            {"WORLD", "Strait of Hormuz", "\"Strait of Hormuz\" OR \"Persian Gulf\" shipping"},
            {"FINANCE", "Federal Reserve and rates", "\"Federal Reserve\" OR \"interest rates\""},
            {"TECH", "AI chips", "\"AI chips\" OR semiconductors"},
            {"SOCIETY", "Climate and extreme weather", "climate OR heatwave OR hurricane"},
            {"POLITICS", "Congress and the budget", "Congress budget OR \"government shutdown\""}};

    /**
     * @param stories     the last two weeks of stories
     * @param read        stories you've read
     * @param starred     stories you starred
     * @param existing    names and search words of your topics and watches (lower case), not suggested again
     * @param topicTexts  your topics' names and search words (lower case): a watch already covered by one isn't suggested
     * @param watches     your watches (name -> search words)
     * @param hidden      suggestions you said you're not interested in (lower-case names)
     * @param topicCount  how many topics you have (few: the starter set is offered)
     */
    public static List<Suggestion> suggest(List<StoryCluster> stories, long now, Predicate<StoryCluster> read, Predicate<StoryCluster> starred,
                                           Collection<String> existing, Collection<String> topicTexts, Map<String, String> watches, Set<String> hidden, int topicCount) {
        List<Suggestion> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (String e : existing) taken.add(e.toLowerCase(Locale.ROOT));
        long week = 7 * 86_400_000L;
        Map<String, int[]> count = new LinkedHashMap<>();       // term -> {read, starred, thisWeek, lastWeek}
        Map<String, Map<Category, Integer>> desks = new HashMap<>();
        Map<String, List<String>> texts = new HashMap<>();
        for (StoryCluster c : stories) {
            boolean r = read.test(c), s = starred.test(c);
            boolean thisWeek = c.lastSeen >= now - week, lastWeek = !thisWeek && c.lastSeen >= now - 2 * week;
            for (String t : c.keyTerms.subList(0, Math.min(6, c.keyTerms.size()))) {
                int[] v = count.computeIfAbsent(t, k -> new int[4]);
                if (r) v[0]++;
                if (s) v[1]++;
                if (thisWeek) v[2]++;
                if (lastWeek) v[3]++;
                desks.computeIfAbsent(t, k -> new EnumMap<>(Category.class)).merge(c.category, 1, Integer::sum);
                List<String> tx = texts.computeIfAbsent(t, k -> new ArrayList<>());
                if (tx.size() < 12) tx.add(c.headline + " " + c.summary);
            }
        }
        List<Map.Entry<String, int[]>> byInterest = new ArrayList<>(count.entrySet());
        byInterest.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[1] * 3 + e.getValue()[0]).reversed());
        for (var e : byInterest) {
            int[] v = e.getValue();
            if (v[1] * 3 + v[0] < 4 || !usable(e.getKey(), texts.get(e.getKey()), taken, hidden)) continue;
            String why = v[1] > 0 ? "You starred " + v[1] + " and read " + v[0] + (v[0] == 1 ? " story" : " stories") + " about it in two weeks"
                    : "You read " + v[0] + " stories about it in two weeks";
            add(out, taken, e.getKey(), mainDesk(desks.get(e.getKey())), why, v[2] + v[3], "reading");
            if (out.stream().filter(x -> x.kind().equals("reading")).count() >= 4) break;
        }
        List<Map.Entry<String, int[]>> byRise = new ArrayList<>(count.entrySet());
        byRise.sort(Comparator.comparingDouble((Map.Entry<String, int[]> e) -> (double) e.getValue()[2] / Math.max(1, e.getValue()[3])).reversed());
        for (var e : byRise) {
            int[] v = e.getValue();
            if (v[2] < 5 || v[2] < 3 * Math.max(1, v[3]) || !usable(e.getKey(), texts.get(e.getKey()), taken, hidden)) continue;
            String why = "Rising: " + v[2] + " stories this week" + (v[3] == 0 ? ", none the week before" : ", " + v[3] + " the week before");
            add(out, taken, e.getKey(), mainDesk(desks.get(e.getKey())), why, v[2] + v[3], "rising");
            if (out.stream().filter(x -> x.kind().equals("rising")).count() >= 4) break;
        }
        for (var w : watches.entrySet()) {
            String n = w.getKey();
            String nl = n.toLowerCase(Locale.ROOT), ql = w.getValue().toLowerCase(Locale.ROOT);
            if (hidden.contains(nl) || topicTexts.stream().anyMatch(t -> t.contains(nl) || t.contains(ql))) continue;
            int matches = 0;
            SearchQuery q = new SearchQuery(w.getValue());
            for (StoryCluster c : stories) if (c.articles.stream().anyMatch(q::matches)) matches++;
            out.add(new Suggestion(n, w.getValue(), Category.WORLD, "You watch \"" + n + "\": as a topic it is also searched across all of Google News and Bing News every day",
                    matches, "watch"));
        }
        if (topicCount < 3) {
            for (String[] s : STARTERS) {
                if (hidden.contains(s[1].toLowerCase(Locale.ROOT)) || taken.contains(s[1].toLowerCase(Locale.ROOT))) continue;
                SearchQuery q = new SearchQuery(s[2]);
                int matches = 0;
                for (StoryCluster c : stories) if (c.articles.stream().anyMatch(q::matches)) matches++;
                out.add(new Suggestion(s[1], s[2], Category.valueOf(s[0]), "A starting point for " + Category.valueOf(s[0]).label(), matches, "starter"));
            }
        }
        return out;
    }

    private static void add(List<Suggestion> out, Set<String> taken, String term, Category desk, String why, int stories, String kind) {
        String q = term.contains(" ") ? "\"" + term + "\"" : term;
        out.add(new Suggestion(term, q, desk, why, stories, kind));
        taken.add(term.toLowerCase(Locale.ROOT));
    }

    /** A name worth a topic (written with capitals in the news, not a common word), not already followed or hidden. */
    static boolean usable(String term, List<String> texts, Set<String> taken, Set<String> hidden) {
        String low = term.toLowerCase(Locale.ROOT);
        if (low.length() < 3 || hidden.contains(low)) return false;
        for (String t : taken) if (t.contains(low) || (low.contains(t) && t.length() >= 4)) return false;
        if (texts == null) return false;
        int caps = 0, lower = 0;
        for (String tx : texts) {
            int i = -1;
            while ((i = tx.indexOf(term, i + 1)) >= 0) caps++;
            String l = tx.toLowerCase(Locale.ROOT);
            i = -1;
            while ((i = l.indexOf(low, i + 1)) >= 0) if (!tx.startsWith(term, i)) lower++;
        }
        return caps >= 1 && lower == 0 && Character.isUpperCase(term.charAt(0));
    }

    private static Category mainDesk(Map<Category, Integer> m) {
        return m == null || m.isEmpty() ? Category.WORLD : Collections.max(m.entrySet(), Map.Entry.comparingByValue()).getKey();
    }
}
