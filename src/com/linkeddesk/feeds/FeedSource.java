package com.linkeddesk.feeds;

import com.linkeddesk.model.Category;

/**
 * One RSS/Atom feed to download: a regular feed from feeds.txt, or a news-search feed generated for a
 * custom topic (then {@code topic} holds the topic's name). {@code filter}, when set, holds search words
 * whose -excluded words are removed after download (Bing ignores them).
 */
public record FeedSource(Category category, String name, String url, String topic, String filter) {
    public FeedSource(Category category, String name, String url) { this(category, name, url, null, null); }
    public FeedSource(Category category, String name, String url, String topic) { this(category, name, url, topic, null); }

    /** For search feeds: the full search words, whose -exclusions are applied after downloading. */

    public boolean isTopic() { return topic != null; }

    /** Search feeds name the outlet in each item, so the outlet is taken from the item, not this feed. */
    public boolean outletPerItem() {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("Google News") || name.startsWith("Bing News") || u.contains("news.google.com") || u.contains("bing.com/news");
    }
}
