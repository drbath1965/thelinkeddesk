package com.linkeddesk.feeds;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.SafeFiles;
import com.linkeddesk.model.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ready-made sets of feeds by region or desk, and Google News editions for other countries (FET-F11). A pack is added
 * to feeds.txt as one marked block, so it can be removed in one click; feeds you already have are left out of it.
 */
public final class FeedPacks {
    private FeedPacks() {}

    public record Pack(String id, String name, String about, List<FeedSource> feeds) {
        @Override public String toString() { return name; }
    }

    static String begin(Pack p) { return "# ---------- Pack: " + p.name() + " [" + p.id() + "] ----------"; }
    static String end(Pack p) { return "# ---------- End of pack: " + p.name() + " [" + p.id() + "] ----------"; }

    private static FeedSource f(Category c, String name, String url) { return new FeedSource(c, name, url); }

    /** English-language Google News editions offered as packs: code, country. */
    static final String[][] GOOGLE_EDITIONS = {{"GB", "UK"}, {"IN", "India"}, {"AU", "Australia"}, {"CA", "Canada"},
            {"IE", "Ireland"}, {"ZA", "South Africa"}, {"SG", "Singapore"}, {"NG", "Nigeria"}};

    public static List<Pack> all() {
        List<Pack> out = new ArrayList<>();
        Category W = Category.WORLD, F = Category.FINANCE, P = Category.POLITICS, T = Category.TECH, D = Category.DEFENSE;
        out.add(new Pack("europe", "Europe", "News from around Europe in English: the EU, Ireland, Poland, the Balkans, Finland and Spain.", List.of(
                f(P, "Euractiv", "https://www.euractiv.com/feed/"),
                f(W, "RTÉ News (Ireland)", "https://www.rte.ie/feeds/rss/?index=/news/"),
                f(W, "Notes from Poland", "https://notesfrompoland.com/feed/"),
                f(W, "Balkan Insight", "https://balkaninsight.com/feed/"),
                f(W, "Yle News (Finland)", "https://feeds.yle.fi/uutiset/v1/recent.rss?publisherIds=YLE_NEWS"),
                f(W, "The Local (Europe)", "https://feeds.thelocal.com/rss/es"))));
        out.add(new Pack("asia", "Asia & Pacific", "Korea, Singapore, Taiwan, Hong Kong, the Philippines and New Zealand.", List.of(
                f(W, "Yonhap (Korea)", "https://en.yna.co.kr/RSS/news.xml"),
                f(W, "The Straits Times World", "https://www.straitstimes.com/news/world/rss.xml"),
                f(W, "Taipei Times", "https://www.taipeitimes.com/xml/index.rss"),
                f(W, "Hong Kong Free Press", "https://hongkongfp.com/feed/"),
                f(W, "Rappler (Philippines)", "https://www.rappler.com/feed/"),
                f(W, "RNZ World (New Zealand)", "https://www.rnz.co.nz/rss/world.xml"))));
        out.add(new Pack("mideast-africa", "Middle East & Africa", "The Gulf, Nigeria and South Africa, beyond the regional feeds you already have.", List.of(
                f(W, "Al-Monitor", "https://www.al-monitor.com/rss"),
                f(W, "Arab News", "https://www.arabnews.com/rss.xml"),
                f(W, "Premium Times (Nigeria)", "https://www.premiumtimesng.com/feed"),
                f(W, "Daily Maverick (South Africa)", "https://www.dailymaverick.co.za/dmrss/"),
                f(W, "Mail & Guardian (South Africa)", "https://mg.co.za/feed/"))));
        out.add(new Pack("americas", "The Americas", "Canada, Mexico, Argentina, Brazil and Latin American security.", List.of(
                f(W, "Global News (Canada)", "https://globalnews.ca/feed/"),
                f(W, "Mexico News Daily", "https://mexiconewsdaily.com/feed/"),
                f(W, "Buenos Aires Times", "https://www.batimes.com.ar/feed"),
                f(W, "The Rio Times (Brazil)", "https://www.riotimesonline.com/feed/"),
                f(W, "InSight Crime", "https://insightcrime.org/feed/"))));
        out.add(new Pack("markets", "Business & markets", "More markets and company news for the Finance desk.", List.of(
                f(F, "Investing.com News", "https://www.investing.com/rss/news.rss"),
                f(F, "Nasdaq Markets", "https://www.nasdaq.com/feed/rssoutbound?category=Markets"),
                f(F, "Business Insider", "https://feeds.businessinsider.com/custom/all"),
                f(F, "The Economist: Finance", "https://www.economist.com/finance-and-economics/rss.xml"))));
        out.add(new Pack("defense", "Defense & Space+", "More defense, Navy, Air Force and space coverage.", List.of(
                f(D, "The War Zone", "https://www.twz.com/feed"),
                f(D, "Air & Space Forces Magazine", "https://www.airandspaceforces.com/feed/"),
                f(D, "USNI News", "https://news.usni.org/feed"),
                f(D, "DefenseScoop", "https://defensescoop.com/feed/"),
                f(D, "Space.com", "https://www.space.com/feeds/all"))));
        out.add(new Pack("tech", "Tech & science+", "More technology and science.", List.of(
                f(T, "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
                f(T, "The Verge", "https://www.theverge.com/rss/index.xml"),
                f(T, "MIT Technology Review", "https://www.technologyreview.com/feed/"),
                f(T, "ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml"),
                f(T, "Nature News", "https://www.nature.com/nature.rss"))));
        String g = System.getProperty("linkeddesk.googleNewsBase", "https://news.google.com");
        for (String[] e : GOOGLE_EDITIONS) {
            String q = "?hl=en-" + e[0] + "&gl=" + e[0] + "&ceid=" + e[0] + ":en";
            out.add(new Pack("google-" + e[0].toLowerCase(Locale.ROOT), "Google News: " + e[1],
                    "Google News's " + e[1] + " edition: top stories, world, the country's own news, and business, each from many outlets.", List.of(
                    f(W, "Google News " + e[1] + " · Top stories", g + "/rss" + q),
                    f(W, "Google News " + e[1] + " · World", g + "/rss/headlines/section/topic/WORLD" + q),
                    f(P, "Google News " + e[1] + " · " + e[1], g + "/rss/headlines/section/topic/NATION" + q),
                    f(F, "Google News " + e[1] + " · Business", g + "/rss/headlines/section/topic/BUSINESS" + q))));
        }
        return out;
    }

    public static Pack find(String id) { return all().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null); }

    /** Ids of the packs in a feed list. */
    public static Set<String> added(String feedsText) {
        Set<String> out = new LinkedHashSet<>();
        for (Pack p : all()) if (feedsText.contains(begin(p))) out.add(p.id());
        return out;
    }

    /** The pack's feeds that aren't in the list already (by address). */
    public static List<FeedSource> missing(Pack p, List<FeedSource> current) {
        Set<String> have = new java.util.HashSet<>();
        for (FeedSource s : current) have.add(s.url().trim());
        return p.feeds().stream().filter(s -> !have.contains(s.url())).toList();
    }

    /** The list with the pack's block appended (only the given feeds). Unchanged if it's already there. */
    public static String withPack(String text, Pack p, List<FeedSource> feeds) {
        if (text.contains(begin(p)) || feeds.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.stripTrailing()).append("\n\n").append(begin(p)).append('\n');
        for (FeedSource s : feeds) sb.append(label(s.category())).append(" | ").append(s.name()).append(" | ").append(s.url()).append('\n');
        return sb.append(end(p)).append('\n').toString();
    }

    /** The list without the pack's block (and the blank line before it). */
    public static String withoutPack(String text, Pack p) {
        int a = text.indexOf(begin(p));
        if (a < 0) return text;
        int b = text.indexOf(end(p), a);
        int stop = b < 0 ? text.length() : b + end(p).length();
        if (stop < text.length() && text.charAt(stop) == '\r') stop++;
        if (stop < text.length() && text.charAt(stop) == '\n') stop++;
        String before = text.substring(0, a).stripTrailing();
        String after = text.substring(stop);
        return before + "\n" + (after.isBlank() ? "" : "\n" + after.stripLeading());
    }

    /** The desk word feeds.txt uses for a category. */
    static String label(Category c) {
        return switch (c) {
            case WORLD -> "World"; case FINANCE -> "Finance"; case POLITICS -> "Politics";
            case SOCIETY -> "Society"; case TECH -> "Tech"; case DEFENSE -> "Defense";
        };
    }

    public static void add(Pack p, List<FeedSource> feeds) throws IOException {
        String text = FeedCatalog.rawText();
        FeedCatalog.saveRawText(withPack(text, p, feeds));
    }

    public static void remove(Pack p) throws IOException {
        if (!Files.exists(AppPaths.feedsFile())) return;
        String text = SafeFiles.readString(AppPaths.feedsFile());
        FeedCatalog.saveRawText(withoutPack(text, p));
    }
}
