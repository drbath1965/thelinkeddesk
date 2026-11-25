package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * People and organization pages (UI-F18): everything saved about one name, with its stories over time, the desks it
 * appears on and the names it most often appears with. Built from a search of the whole archive.
 */
public record EntityPage(String name, List<StoryCluster> stories, int articles, SortedMap<YearMonth, Integer> byMonth,
                         Map<Category, Integer> byDesk, List<Map.Entry<String, Integer>> withNames, long first, long last) {

    public static EntityPage of(String name, List<StoryCluster> found, ZoneId zone) {
        List<StoryCluster> stories = new ArrayList<>(found);
        stories.sort(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed());
        SortedMap<YearMonth, Integer> months = new TreeMap<>();
        Map<Category, Integer> desks = new EnumMap<>(Category.class);
        Map<String, Integer> names = new HashMap<>();
        String self = name.toLowerCase(Locale.ROOT).replace("\"", "").strip();
        int articles = 0;
        long first = Long.MAX_VALUE, last = 0;
        for (StoryCluster c : stories) {
            articles += c.articles.size();
            for (Article a : c.articles) {
                months.merge(YearMonth.from(Instant.ofEpochMilli(a.published).atZone(zone)), 1, Integer::sum);
                first = Math.min(first, a.published);
                last = Math.max(last, a.published);
            }
            desks.merge(c.category, 1, Integer::sum);
            if (c.keyTerms != null) for (String t : c.keyTerms) {
                String low = t.toLowerCase(Locale.ROOT);
                if (t.isEmpty() || !Character.isUpperCase(t.charAt(0)) || low.contains(self) || self.contains(low)) continue;
                names.merge(t, 1, Integer::sum);
            }
        }
        // Only real names: a term that also appears in lower case ("drone", "attack") is an ordinary word.
        StringBuilder all = new StringBuilder();
        for (StoryCluster c : stories) for (Article a : c.articles) all.append(' ').append(a.title).append(". ").append(a.summary);
        String text = all.toString(), lower = text.toLowerCase(Locale.ROOT);
        names.keySet().removeIf(t -> {
            String low = t.toLowerCase(Locale.ROOT);
            int i = -1;
            while ((i = lower.indexOf(low, i + 1)) >= 0) {
                boolean word = (i == 0 || !Character.isLetter(lower.charAt(i - 1))) && (i + low.length() >= lower.length() || !Character.isLetter(lower.charAt(i + low.length())));
                if (word && !text.startsWith(t, i)) return true;
            }
            return false;
        });
        List<Map.Entry<String, Integer>> with = new ArrayList<>(names.entrySet());
        with.removeIf(e -> e.getValue() < 2 && stories.size() > 3);
        with.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        if (with.size() > 12) with = new ArrayList<>(with.subList(0, 12));
        return new EntityPage(name, stories, articles, months, desks, with, first == Long.MAX_VALUE ? 0 : first, last);
    }
}
