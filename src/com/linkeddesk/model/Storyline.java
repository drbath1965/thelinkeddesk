package com.linkeddesk.model;

import java.util.List;

/** One event followed across days: stories that continue each other, oldest first. */
public record Storyline(List<StoryCluster> stories) {
    public StoryCluster first() { return stories.get(0); }
    public StoryCluster latest() { return stories.get(stories.size() - 1); }
    public long days() { return Math.max(1, Math.round((latest().lastSeen - first().firstSeen) / 86_400_000.0) + 1); }
    public int articleCount() { int n = 0; for (StoryCluster c : stories) n += c.articles.size(); return n; }
}
