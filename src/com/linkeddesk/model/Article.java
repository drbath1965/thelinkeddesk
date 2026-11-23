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

    /** What it is when not an ordinary article: "video", "audio" (podcast), "post" (social) or "" (FET-F06). */
    public final String kind;
    /** The full text, when the publisher's feed includes it (FET-F07); kept apart from the archive line, may be "". */
    public final String fullText;

    private volatile Category category;
    /** Set on a translated copy (ANL-F11): the language it was written in, and its own headline. */
    public volatile String lang = "";
    public volatile String originalTitle = "";

    public Article(String id, String url, String title, String summary, String source,
                   Category feedCategory, long published, long fetched) {
        this(id, url, title, summary, source, feedCategory, published, fetched, "", "");
    }

    public Article(String id, String url, String title, String summary, String source,
                   Category feedCategory, long published, long fetched, String kind, String fullText) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.summary = summary == null ? "" : summary;
        this.source = source;
        this.feedCategory = feedCategory;
        this.published = published;
        this.fetched = fetched;
        this.category = feedCategory;
        this.kind = kind == null ? "" : kind;
        this.fullText = fullText == null ? "" : fullText;
    }

    /** "Video", "Podcast", "Post" or "" for an ordinary article. */
    public String kindLabel() {
        return switch (kind) { case "video" -> "Video"; case "audio" -> "Podcast"; case "post" -> "Post"; default -> ""; };
    }

    /** The same article with other text (e.g. translated), keeping everything else. */
    public Article withText(String title, String summary) {
        Article a = new Article(id, url, title, summary, source, feedCategory, published, fetched, kind, fullText);
        a.setCategory(category);
        return a;
    }

    public Category category() { return category; }
    public void setCategory(Category c) { this.category = c; }

    @Override public String toString() { return source + ": " + title; }
}
