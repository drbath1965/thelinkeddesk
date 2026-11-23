package com.linkeddesk.ai;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story citations in Claude's answers: stories are sent as [S1], [S2]…; an answer may only cite those.
 * A citation to a story that wasn't sent is removed and reported, so every citation shown points to a real story.
 * Stories are remembered by their article ids, so a saved digest still finds them after the next regrouping.
 */
public final class Cite {
    private Cite() {}

    public static final Pattern TAG = Pattern.compile("\\[\\s*S(\\d+)\\s*\\]");

    /** Numbers the stories S1, S2… */
    public static Map<String, StoryCluster> number(List<StoryCluster> stories) {
        Map<String, StoryCluster> m = new LinkedHashMap<>();
        for (int i = 0; i < stories.size(); i++) m.put("S" + (i + 1), stories.get(i));
        return m;
    }

    /** S# -> article ids, to save with an answer. */
    public static Map<String, Set<String>> ids(Map<String, StoryCluster> refs) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (var e : refs.entrySet()) {
            Set<String> s = new LinkedHashSet<>();
            for (Article a : e.getValue().articles) { s.add(a.id); if (s.size() >= 30) break; }
            m.put(e.getKey(), s);
        }
        return m;
    }

    /** Removes citations to stories that weren't sent; notes each removed one in {@code problems}. */
    public static String clean(String text, Set<String> known, List<String> problems) {
        Matcher m = TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = "S" + m.group(1);
            if (known.contains(id)) m.appendReplacement(sb, "[" + id + "]");
            else { problems.add("Claude cited " + id + ", which wasn't one of the stories sent; removed."); m.appendReplacement(sb, ""); }
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+([.,;:])", "$1").replaceAll(" {2,}", " ").trim();
    }

    /** The ids cited in a text, in order of first use. */
    public static List<String> cited(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TAG.matcher(text);
        while (m.find()) out.add("S" + m.group(1));
        return new ArrayList<>(out);
    }

    /** Finds the current story holding any of these article ids. */
    public static StoryCluster find(Set<String> ids, List<StoryCluster> stories) {
        for (StoryCluster c : stories) for (Article a : c.articles) if (ids.contains(a.id)) return c;
        return null;
    }
}
