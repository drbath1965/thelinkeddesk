package com.linkeddesk.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** A recurring topic (a person, place or issue) that ties several linked stories together. */
public record StoryThread(String topic, List<StoryCluster> stories, int linkCount) {
    public Set<Category> desks() {
        Set<Category> s = EnumSet.noneOf(Category.class);
        for (StoryCluster c : stories) s.add(c.category);
        return s;
    }
}
