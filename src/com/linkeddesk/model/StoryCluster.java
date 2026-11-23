package com.linkeddesk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A single news event: one or more articles, usually from different outlets, about the same thing. */
public final class StoryCluster {
    public final String id;
    public final List<Article> articles = new ArrayList<>();
    public final List<StoryLink> links = new ArrayList<>();
    /** Display forms of the terms that best describe this story, strongest first. */
    public List<String> keyTerms = List.of();
    public String headline = "";
    public String summary = "";
    public Category category = Category.WORLD;
    public long firstSeen = Long.MAX_VALUE;
    public long lastSeen = Long.MIN_VALUE;
    /** A second desk the story also shows in (e.g. a sanctions story in World and Finance), or null. */
    public Category also;
    /** True when you moved this story (or its source/keyword) to its desk. */
    public boolean deskSetByYou;
    /** The earlier story this one continues (day 2 of the same event), or null. */
    public StoryCluster continues;
    /** Later stories that continue this one. */
    public final List<StoryCluster> continuedBy = new ArrayList<>();
    /** Links you marked wrong, hidden from the list. */
    public int hiddenLinks;

    public StoryCluster(String id) { this.id = id; }

    public void add(Article a) {
        articles.add(a);
        firstSeen = Math.min(firstSeen, a.published);
        lastSeen = Math.max(lastSeen, a.published);
    }

    public Set<String> sources() {
        Set<String> s = new LinkedHashSet<>();
        for (Article a : articles) s.add(a.source);
        return s;
    }

    public int sourceCount() { return sources().size(); }

    /** True if the story shows in this desk (its main desk or its second desk). */
    public boolean inDesk(Category c) { return category == c || also == c; }

    /** The whole storyline this story belongs to, oldest first (just this story when it stands alone). */
    public List<StoryCluster> storyline() {
        StoryCluster root = this;
        Set<StoryCluster> guard = new java.util.HashSet<>();
        while (root.continues != null && guard.add(root)) root = root.continues;
        List<StoryCluster> out = new ArrayList<>();
        java.util.ArrayDeque<StoryCluster> todo = new java.util.ArrayDeque<>(List.of(root));
        Set<StoryCluster> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            StoryCluster c = todo.poll();
            if (!seen.add(c)) continue;
            out.add(c);
            todo.addAll(c.continuedBy);
        }
        out.sort(java.util.Comparator.comparingLong(c -> c.firstSeen));
        return out;
    }

    public boolean hasCrossDeskLink() {
        for (StoryLink l : links) if (l.crossDesk()) return true;
        return false;
    }

    public List<Article> articlesNewestFirst() {
        List<Article> copy = new ArrayList<>(articles);
        copy.sort((x, y) -> Long.compare(y.published, x.published));
        return Collections.unmodifiableList(copy);
    }
}
