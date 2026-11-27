package com.linkeddesk.feeds;

import com.linkeddesk.app.Settings;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;

/**
 * How much news is downloaded (FET-F10). Feeds only list their newest 10-50 items, so the main way to get more is to
 * download more often: busy feeds then lose nothing between downloads. Higher settings also search topics in more
 * Google News editions. Optional limits cap the articles kept per feed per download and per day.
 */
public final class DownloadVolume {
    private DownloadVolume() {}

    public enum Level {
        LIGHT("Light", 0, 15, 600, 1, "Once a day, at most 15 new articles per feed and 600 a day."),
        STANDARD("Standard", 0, 0, 0, 1, "Once a day, everything the feeds list."),
        MORE("More", 4, 0, 0, 2, "Every 4 hours, so busy feeds lose nothing in between; topics also searched in the UK edition."),
        MAXIMUM("Maximum", 1, 0, 0, 3, "Every hour; topics searched in 5 English-language editions (US, UK, India, Australia, Canada)."),
        CUSTOM("Custom", -1, -1, -1, -1, "Your own schedule and limits.");
        public final String label, about;
        final int everyHours, maxPerFeed, maxPerDay, topicDepth;
        Level(String label, int everyHours, int maxPerFeed, int maxPerDay, int topicDepth, String about) {
            this.label = label; this.everyHours = everyHours; this.maxPerFeed = maxPerFeed; this.maxPerDay = maxPerDay; this.topicDepth = topicDepth; this.about = about;
        }
        @Override public String toString() { return label; }

        public static Level parse(String s) {
            for (Level l : values()) if (l.name().equalsIgnoreCase(s == null ? "" : s.trim())) return l;
            return STANDARD;
        }
    }

    /** What a level means in practice: download every N hours (0 = once a day), limits (0 = none), topic search depth. */
    public record Plan(Level level, int everyHours, int maxPerFeed, int maxPerDay, int topicDepth) {
        public int downloadsPerDay() { return everyHours <= 0 ? 1 : Math.max(1, 24 / everyHours); }
        public String schedule() { return everyHours <= 0 ? "once a day" : everyHours == 1 ? "every hour" : "every " + everyHours + " hours"; }
    }

    public static Plan of(Level l, int customEvery, int customPerFeed, int customPerDay, int customDepth) {
        if (l == Level.CUSTOM) return new Plan(l, Math.max(0, customEvery), Math.max(0, customPerFeed), Math.max(0, customPerDay), Math.max(1, Math.min(3, customDepth)));
        return new Plan(l, l.everyHours, l.maxPerFeed, l.maxPerDay, l.topicDepth);
    }

    public static Plan of(Settings s) {
        return of(Level.parse(s.downloadVolume()), s.downloadEveryHours(), s.maxPerFeed(), s.maxPerDay(), s.topicDepth());
    }

    /** A feed's recent behaviour: how many items it lists, how fast it publishes, whether it's a topic search. */
    public record FeedStat(int itemsListed, double publishedPerDay, boolean topic) {}

    public record Estimate(int perDay, long bytesPerYear, int feeds) {
        public String size() {
            double gb = bytesPerYear / 1e9;
            return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb) : String.format(Locale.ROOT, "%d MB", Math.max(1, bytesPerYear / 1_000_000));
        }
    }

    /**
     * New articles a day under a plan. Each feed catches min(what it publishes, what it lists × downloads a day): a
     * feed listing 20 items that publishes 60 a day catches 20 once a day but all 60 every 4 hours. Topics count once
     * per Google News edition searched. Feeds with no history yet count 20 a day.
     */
    public static Estimate estimate(Plan p, Collection<FeedStat> feeds, int topicSearches, double bytesPerArticle) {
        double total = 0;
        for (FeedStat f : feeds) {
            int listed = f.itemsListed() > 0 ? f.itemsListed() : 20;
            double rate = f.publishedPerDay() > 0 ? f.publishedPerDay() : listed;
            double caught = Math.min(rate, (double) listed * p.downloadsPerDay());
            if (p.maxPerFeed() > 0) caught = Math.min(caught, (double) p.maxPerFeed() * p.downloadsPerDay());
            if (f.topic()) caught *= editions(p.topicDepth()) * 0.6 + 0.4;   // extra editions overlap a little
            total += caught;
        }
        if (p.maxPerDay() > 0) total = Math.min(total, p.maxPerDay());
        int perDay = (int) Math.round(total);
        return new Estimate(perDay, Math.round(perDay * 365.0 * (bytesPerArticle > 0 ? bytesPerArticle : 1100)), feeds.size() + topicSearches);
    }

    /** Google News editions searched for each English topic at this depth. */
    public static int editions(int depth) { return depth >= 3 ? 5 : depth == 2 ? 2 : 1; }

    /** The newest {@code max} items not saved yet (0 = all of them, unchanged). Older new items are left for next time or dropped. */
    public static java.util.List<com.linkeddesk.model.Article> limitPerFeed(java.util.List<com.linkeddesk.model.Article> items,
            java.util.function.Predicate<com.linkeddesk.model.Article> isNew, int max) {
        if (max <= 0) return items;
        java.util.List<com.linkeddesk.model.Article> fresh = new java.util.ArrayList<>(items.stream().filter(isNew).toList());
        if (fresh.size() <= max) return items;
        fresh.sort((a, b) -> Long.compare(b.published, a.published));
        java.util.Set<com.linkeddesk.model.Article> keep = new java.util.HashSet<>(fresh.subList(0, max));
        return items.stream().filter(a -> !isNew.test(a) || keep.contains(a)).toList();
    }

    /**
     * Shares a day's remaining allowance between feeds, most important first: each feed in turn gets as many of its
     * new items as are left. Returns how many new items each feed may keep (same order as given).
     */
    public static int[] allocate(int[] newCounts, double[] priority, int budget) {
        Integer[] order = new Integer[newCounts.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(priority[b], priority[a]));
        int[] out = new int[newCounts.length];
        int left = Math.max(0, budget);
        for (int i : order) { out[i] = Math.min(newCounts[i], left); left -= out[i]; }
        return out;
    }

    /** Articles kept today so far and the day they're counted for (for the daily limit). */
    public static int remainingToday(Plan p, LocalDate countedDay, int countedToday, LocalDate today) {
        if (p.maxPerDay() <= 0) return Integer.MAX_VALUE;
        int used = today.equals(countedDay) ? countedToday : 0;
        return Math.max(0, p.maxPerDay() - used);
    }
}
