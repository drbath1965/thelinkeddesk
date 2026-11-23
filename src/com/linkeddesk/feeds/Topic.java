package com.linkeddesk.feeds;

import com.linkeddesk.model.Category;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * A custom topic: news about it is searched for and downloaded on every run.
 * The query uses the usual news-search syntax: words, "exact phrases", OR, and -excluded words.
 */
public record Topic(Category desk, String name, String query, String region, boolean paused) {

    public Topic {
        region = region == null || region.isBlank() ? "en-US" : region.trim();
    }

    /**
     * The news-search feeds downloaded for this topic.
     *  - Google News gets the search words as written (it understands "phrases", OR and -words).
     *  - Bing News returns nothing for OR and very little with -words (checked Nov 2026), so it gets one
     *    request per OR alternative, without the -words.
     * Either way the -words are applied by the app after downloading.
     */
    public List<FeedSource> feeds() {
        String[] r = region.split("-");
        String hl = r[0].toLowerCase(Locale.ROOT);
        String gl = r.length > 1 ? r[1].toUpperCase(Locale.ROOT) : "US";
        String google = System.getProperty("linkeddesk.googleNewsBase", "https://news.google.com");
        String bing = System.getProperty("linkeddesk.bingNewsBase", "https://www.bing.com");
        List<FeedSource> out = new ArrayList<>();
        out.add(new FeedSource(desk, "Google News · " + name,
                google + "/rss/search?q=" + enc(query) + "&hl=" + hl + "-" + gl + "&gl=" + gl + "&ceid=" + gl + ":" + hl, name, query));
        List<String> alts = alternatives(query);
        for (int i = 0; i < alts.size() && i < MAX_BING_ALTERNATIVES; i++) {
            out.add(new FeedSource(desk, "Bing News · " + name + (alts.size() > 1 ? " (" + alts.get(i) + ")" : ""),
                    bing + "/news/search?q=" + enc(alts.get(i)) + "&format=rss&setlang=" + hl + "-" + gl.toLowerCase(Locale.ROOT), name, query));
        }
        return out;
    }

    static final int MAX_BING_ALTERNATIVES = 6;
    private static final Pattern TOKEN = Pattern.compile("-?\"[^\"]*\"?|\\S+");

    /** Splits search words at OR into alternatives, dropping -excluded words: a OR "b c" -d  ->  [a, "b c"]. */
    static List<String> alternatives(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Matcher m = TOKEN.matcher(query);
        while (m.find()) {
            String t = m.group();
            if (t.equals("OR") || t.equals("|")) { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } continue; }
            if (t.startsWith("-") && t.length() > 1) continue;
            if (cur.length() > 0) cur.append(' ');
            cur.append(t);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** One line of topics.txt. */
    public String toLine() {
        return String.join(" | ", desk.name().charAt(0) + desk.name().substring(1).toLowerCase(Locale.ROOT),
                name, query, region, paused ? "paused" : "on");
    }
}
