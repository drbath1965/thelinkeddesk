package com.linkeddesk.model;

/** One downloaded news item. Immutable apart from the category the analyzer assigns. */
public final class Article {
    public final String id;          // stable hash of the normalized URL
    public final String url;
    public final String title;
    public final String summary;
    public final String source;      // outlet name, e.g. "BBC World"
    public final Category feedCategory;
    public final long published;     // epoch millis
    public final long fetched;       // epoch millis

    private volatile Category category;

    public Article(String id, String url, String title, String summary, String source,
                   Category feedCategory, long published, long fetched) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.summary = summary == null ? "" : summary;
        this.source = source;
        this.feedCategory = feedCategory;
        this.published = published;
        this.fetched = fetched;
        this.category = feedCategory;
    }

    public Category category() { return category; }
    public void setCategory(Category c) { this.category = c; }

    @Override public String toString() { return source + ": " + title; }
}
