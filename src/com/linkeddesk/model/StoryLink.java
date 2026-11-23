package com.linkeddesk.model;

import java.util.List;

/**
 * A directed view of a link between two stories, with the shared terms that justify it.
 * {@code confirmed}: you marked the link as right, so it is always shown.
 */
public record StoryLink(StoryCluster from, StoryCluster to, double score, List<String> sharedTerms, boolean confirmed) {
    public StoryLink(StoryCluster from, StoryCluster to, double score, List<String> sharedTerms) {
        this(from, to, score, sharedTerms, false);
    }

    public boolean crossDesk() { return from.category != to.category; }

    /** True when one story continues the other (the same event on a later day). */
    public boolean sameStoryline() { return from.continues == to || to.continues == from; }

    /** Plain-language strength used in the UI. */
    public String strength() {
        if (score >= 0.30) return "Strong";
        if (score >= 0.18) return "Moderate";
        return "Possible";
    }
}
