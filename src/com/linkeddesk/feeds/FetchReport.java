package com.linkeddesk.feeds;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Outcome of one download run: per-feed counts, errors, unchanged and paused feeds. */
public final class FetchReport {
    public record FeedResult(FeedSource feed, int items, int added, String error, boolean notModified, boolean paused, int attempts) {
        public FeedResult(FeedSource feed, int items, int added, String error) { this(feed, items, added, error, false, false, 1); }
    }

    public final Instant started = Instant.now();
    public Instant finished;
    public final List<FeedResult> results = new ArrayList<>();
    public int totalAdded;
    /** The articles this run added (for watchlist notifications and "new since"). Kept in memory only. */
    public final List<com.linkeddesk.model.Article> addedArticles = new ArrayList<>();
    public boolean cancelled;
    public int expected;

    public synchronized void add(FeedResult r) { results.add(r); }

    public synchronized long failures() { return results.stream().filter(r -> r.error() != null && !"Cancelled".equals(r.error())).count(); }
    public synchronized long unchanged() { return results.stream().filter(FeedResult::notModified).count(); }
    public synchronized long paused() { return results.stream().filter(FeedResult::paused).count(); }
    public synchronized int totalItems() { return results.stream().mapToInt(FeedResult::items).sum(); }

    public synchronized String summary() {
        long checked = results.stream().filter(r -> !r.paused() && !"Cancelled".equals(r.error())).count();
        StringBuilder sb = new StringBuilder(String.format("%d feeds checked, %d items read, %d new articles, %d feeds failed",
                checked, totalItems(), totalAdded, failures()));
        if (unchanged() > 0) sb.append(", ").append(unchanged()).append(" unchanged");
        if (paused() > 0) sb.append(", ").append(paused()).append(" paused");
        if (cancelled) sb.append(" — cancelled, ").append(results.stream().filter(r -> "Cancelled".equals(r.error())).count() + Math.max(0, expected - results.size()))
                .append(" feeds not downloaded");
        return sb.toString();
    }

    public synchronized String detail() {
        StringBuilder sb = new StringBuilder(summary()).append('\n');
        results.stream()
                .sorted((a, b) -> a.feed().name().compareToIgnoreCase(b.feed().name()))
                .forEach(r -> sb.append(String.format("  %-40s %4d items  %4d new  %s%n",
                        r.feed().name(), r.items(), r.added(),
                        r.paused() ? "PAUSED (failing; retried weekly)"
                                : r.notModified() ? "unchanged"
                                : r.error() == null ? (r.attempts() > 1 ? "ok after " + r.attempts() + " tries" : "")
                                : "ERROR: " + r.error())));
        return sb.toString();
    }
}
