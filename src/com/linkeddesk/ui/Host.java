package com.linkeddesk.ui;

import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.service.NewsService;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** What the main window offers its tabs and dialogs. */
interface Host {
    NewsService service();

    /** "Ask Claude why" for a link. */
    void explain(StoryCluster from, StoryLink link);

    /** A job that downloads; it gets a progress callback and a cancel flag. */
    interface DownloadJob<T> { T run(Consumer<String> progress, BooleanSupplier cancelled) throws Exception; }

    /**
     * Runs a download job in the background, one at a time, with the toolbar button turned into Cancel.
     * {@code after} runs on the Swing thread with the result (or is skipped on failure, which is reported).
     */
    <T> void runDownload(String busyText, DownloadJob<T> job, Consumer<T> after);

    /** New articles were saved: re-group the main view. */
    void articlesChanged();

    void status(String text);

    /** Your corrections changed (link verdicts, desk rules, word lists, thresholds): regroup every view. */
    void correctionsChanged();

    /** The watchlist changed or was looked at: refresh highlights. */
    default void watchesChanged() { }

    /** The watches a story matches (for the list badge), and whether it has articles you haven't seen in that watch. */
    default java.util.List<String> watchesFor(StoryCluster c) { return java.util.List.of(); }
    default boolean newForWatch(StoryCluster c) { return false; }

    /** Opens the "watch a subject" editor with these words. */
    default void addWatch(String words) { }

    /** Copies a story with its sources to the clipboard; saves it as a file (EXP-F01). */
    default void copyStory(StoryCluster c) { }
    /** One article from a story and its linked stories (AI-F10). */
    default void combinedArticle(StoryCluster c) { }
    /** Find connections with Claude (AI-F09): shows the cost, asks, then shows what Claude found on the story. */
    default void findConnections(StoryCluster c) { }
    /** The story on screen that contains any of these articles, or null. */
    default StoryCluster storyWithArticles(java.util.Set<String> ids) { return null; }
    /** Explain a subject (AI-F08) from these stories, or (seed empty) from a search of the archive. */
    default void explainSubject(String subject, java.util.List<StoryCluster> seed) { }
    /** How the outlets in a story framed it (AI-F07). */
    default void framing(StoryCluster c) { }
    default void exportStory(StoryCluster c) { }

    /** A story was starred or unstarred: refresh the Saved tab. */
    default void savedChanged() { }
}
