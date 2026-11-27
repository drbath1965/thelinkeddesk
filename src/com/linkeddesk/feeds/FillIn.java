package com.linkeddesk.feeds;

import com.linkeddesk.model.Category;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Filling in past days (FET-F12): Google News searches limited to one day each ("after:" / "before:"), for your
 * topics and for each desk. Feeds only list recent items, so days the PC was off are otherwise lost.
 */
public final class FillIn {
    private FillIn() {}

    public static final int MAX_DAYS = 30;
    /** Days filled in automatically after the PC was off (more can be filled in by hand). */
    public static final int AUTO_DAYS = 7;
    /** New articles one dated search usually adds after duplicates (Google lists up to 100; many are already saved). */
    static final int NEW_PER_SEARCH = 35;

    /** What each desk is searched for. */
    public static final Map<Category, String> DESK_QUERIES = Map.of(
            Category.WORLD, "world news",
            Category.FINANCE, "markets OR economy OR stocks",
            Category.POLITICS, "politics OR election OR congress OR parliament",
            Category.SOCIETY, "climate OR health OR education",
            Category.TECH, "technology OR science OR AI",
            Category.DEFENSE, "defense OR military OR space launch");

    /** The past days to fill in: the {@code days} days before today, oldest first. */
    public static List<LocalDate> days(LocalDate today, int days) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = Math.min(MAX_DAYS, Math.max(0, days)); i >= 1; i--) out.add(today.minusDays(i));
        return out;
    }

    /** The whole days missed: after the day of the last download, up to yesterday (none if it was yesterday or today). */
    public static List<LocalDate> missedDays(LocalDate lastDownload, LocalDate today, int max) {
        List<LocalDate> out = new ArrayList<>();
        if (lastDownload == null) return out;
        for (LocalDate d = lastDownload.plusDays(1); d.isBefore(today); d = d.plusDays(1)) out.add(d);
        return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
    }

    /** One dated search per topic and per desk for each day. */
    public static List<FeedSource> feeds(Collection<Topic> topics, Collection<Category> desks, List<LocalDate> days) {
        String google = System.getProperty("linkeddesk.googleNewsBase", "https://news.google.com");
        List<FeedSource> out = new ArrayList<>();
        for (LocalDate d : days) {
            for (Topic t : topics) out.add(t.datedFeed(d));
            for (Category c : desks) {
                String q = DESK_QUERIES.get(c) + " after:" + d + " before:" + d.plusDays(1);
                out.add(new FeedSource(c, "Google News · " + c.label() + " (" + d + ")",
                        google + "/rss/search?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8) + "&hl=en-US&gl=US&ceid=US:en"));
            }
        }
        return out;
    }

    public record Estimate(int searches, int articles, int seconds) {}

    /** Searches, likely new articles, and roughly how long it takes (searches run 6 at a time, a second or two each). */
    public static Estimate estimate(int days, int topics, int desks) {
        int searches = Math.max(0, days) * (topics + desks);
        return new Estimate(searches, searches * NEW_PER_SEARCH, (int) Math.ceil(searches / 6.0 * 1.5));
    }
}
