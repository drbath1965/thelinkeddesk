package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.Storyline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The morning page: the top stories of the last 24 hours in each desk (by how many outlets cover them and how
 * fast coverage is growing), the links between desks that are new since yesterday, and storylines that moved on.
 */
public record Briefing(long now, Map<Category, List<Ranked>> desks, List<StoryLink> newLinks, List<Storyline> storylines,
                       int articles, int outlets, int stories, Analyzer.Result result) {
    public static final int PER_DESK = 5;
    static final long DAY = 24L * 3600_000;

    /** A story with its score: outlets, plus extra for articles in the last 24 h and more for the last 6 h. */
    public record Ranked(StoryCluster story, double score, int last24h, int last6h) {}

    /** Builds the briefing from an analysis that covers at least the last two days. */
    public static Briefing build(Analyzer.Result r, long now) {
        long since = now - DAY, recent = now - 6 * 3600_000L;
        Map<Category, List<Ranked>> desks = new EnumMap<>(Category.class);
        for (Category c : Category.values()) desks.put(c, new ArrayList<>());
        int articles = 0;
        Set<String> outlets = new HashSet<>();
        int stories = 0;
        for (StoryCluster s : r.stories()) {
            if (s.lastSeen < since) continue;
            int d = 0, h6 = 0;
            for (Article a : s.articles) {
                if (a.published >= since) { d++; outlets.add(a.source); }
                if (a.published >= recent) h6++;
            }
            if (d == 0) continue;
            stories++;
            articles += d;
            double score = s.sourceCount() + 0.5 * d + 0.5 * h6;
            Ranked rk = new Ranked(s, score, d, h6);
            desks.get(s.category).add(rk);
            if (s.also != null) desks.get(s.also).add(rk);
        }
        Comparator<Ranked> best = Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparingLong((Ranked x) -> x.story().lastSeen).reversed());
        for (var e : desks.entrySet()) {
            e.getValue().sort(best);
            if (e.getValue().size() > PER_DESK) e.setValue(new ArrayList<>(e.getValue().subList(0, PER_DESK)));
        }

        // Links between desks that are new: both stories active in the last day, at least one of them new today.
        List<StoryLink> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StoryCluster s : r.stories()) {
            for (StoryLink l : s.links) {
                if (!l.crossDesk()) continue;
                StoryCluster o = l.to();
                if (s.lastSeen < since || o.lastSeen < since) continue;
                if (s.firstSeen < since && o.firstSeen < since) continue;
                String key = s.id.compareTo(o.id) < 0 ? s.id + "|" + o.id : o.id + "|" + s.id;
                if (seen.add(key)) links.add(l);
            }
        }
        links.sort(Comparator.comparing((StoryLink l) -> !l.confirmed()).thenComparing(Comparator.comparingDouble(StoryLink::score).reversed()));
        if (links.size() > 10) links = new ArrayList<>(links.subList(0, 10));

        // Storylines that moved on today.
        List<Storyline> lines = new ArrayList<>();
        for (Storyline sl : r.storylines()) if (sl.latest().firstSeen >= since) lines.add(sl);
        if (lines.size() > 5) lines = new ArrayList<>(lines.subList(0, 5));
        return new Briefing(now, desks, links, lines, articles, outlets.size(), stories, r);
    }

    public boolean isEmpty() { return stories == 0; }
}
