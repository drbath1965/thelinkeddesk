package com.linkeddesk.feeds;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.model.Category;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes feeds.txt. Each line is "Category | Source name | URL"; lines starting with # are comments.
 * There is no limit on the number of feeds or articles.
 */
public final class FeedCatalog {
    private FeedCatalog() {}

    public static List<FeedSource> load() {
        Path f = AppPaths.feedsFile();
        try {
            Path upgraded = AppPaths.home().resolve(".feeds-v1.1");
            Path upgraded18 = AppPaths.home().resolve(".feeds-v1.8");
            if (!Files.exists(f)) {
                Files.writeString(f, DEFAULT_FEEDS, StandardCharsets.UTF_8);
                Files.writeString(upgraded, "new install");
                Files.writeString(upgraded18, "new install");
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (!Files.exists(upgraded)) {
                // Upgrade from v1.0: add the new feeds once. Never again, even if you later delete them.
                if (!text.contains(V11_MARKER)) {
                    StringBuilder fixed = new StringBuilder();
                    for (String line : text.split("\\R", -1)) {
                        String why = RETIRED.get(feedUrl(line));
                        fixed.append(why == null ? line : "# Turned off in v1.1 (" + why + "): " + line).append('\n');
                    }
                    text = fixed.toString().stripTrailing() + "\n" + V11_ADDITIONS;
                    Files.writeString(f, text, StandardCharsets.UTF_8);
                }
                Files.writeString(upgraded, "added v1.1 feeds");
            }
            if (!Files.exists(upgraded18)) {
                // v1.8: the Defense & Space desk. Move the defense and space feeds to it, add four more. Once only.
                if (!text.contains(V18_MARKER)) {
                    StringBuilder fixed = new StringBuilder();
                    for (String line : text.split("\\R", -1)) {
                        String[] p = line.split("\\|");
                        if (!line.strip().startsWith("#") && p.length >= 3 && TO_DEFENSE.contains(p[1].strip())) line = "Defense |" + line.substring(line.indexOf('|') + 1);
                        fixed.append(line).append('\n');
                    }
                    text = fixed.toString().stripTrailing() + "\n" + V18_ADDITIONS;
                    Files.writeString(f, text, StandardCharsets.UTF_8);
                }
                Files.writeString(upgraded18, "added v1.8 feeds");
            }
            return parse(text);
        } catch (IOException e) {
            return parse(DEFAULT_FEEDS);
        }
    }

    /** The feed list as text for the editor. Throws if feeds.txt exists but can't be read (so the defaults never replace it). */
    public static String rawText() throws IOException {
        load();
        String s = com.linkeddesk.app.SafeFiles.readString(AppPaths.feedsFile());
        return s.isEmpty() && !Files.exists(AppPaths.feedsFile()) ? DEFAULT_FEEDS : s;
    }

    public static void saveRawText(String text) throws IOException {
        com.linkeddesk.app.SafeFiles.write(AppPaths.feedsFile(), text);
    }

    public static List<FeedSource> parse(String text) {
        List<FeedSource> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] parts = l.split("\\|");
            if (parts.length < 3) continue;
            String url = parts[2].strip();
            if (url.isEmpty()) continue;
            out.add(new FeedSource(Category.parse(parts[0]), parts[1].strip(), url));
        }
        return out;
    }

    /** Feeds from v1.0 that stopped working; turned off (commented out) when upgrading. */
    static final java.util.Map<String, String> RETIRED = java.util.Map.of(
            "https://finance.yahoo.com/news/rssindex", "Yahoo stopped this feed in Oct 2026; replaced by Financial Times Markets");

    private static String feedUrl(String line) {
        String[] p = line.split("\\|");
        return !line.strip().startsWith("#") && p.length >= 3 ? p[2].strip() : "";
    }

    static final String V11_MARKER = "# ---------- Added in v1.1: regional, defense and space coverage ----------";

    /** New in v1.1. Appended once to an existing feeds.txt; check them with News > Feed health > Test all feeds. */
    static final String V11_ADDITIONS = "\n" + V11_MARKER + "\n" + """
            World | AllAfrica | https://allafrica.com/tools/headlines/rdf/latest/headlines.rdf
            World | Africanews | https://www.africanews.com/feed/rss
            Finance | Financial Times Markets | https://www.ft.com/markets?format=rss
            World | Dawn (Pakistan) | https://www.dawn.com/feeds/home
            World | The Hindu International | https://www.thehindu.com/news/international/feeder/default.rss
            World | Times of Israel | https://www.timesofisrael.com/feed/
            World | Middle East Eye | https://www.middleeasteye.net/rss
            World | CNA (Singapore) | https://www.channelnewsasia.com/rssfeeds/8395986
            World | The Diplomat | https://thediplomat.com/feed/
            World | Kyiv Independent | https://kyivindependent.com/feed/
            World | The Moscow Times | https://www.themoscowtimes.com/rss/news
            World | MercoPress (South America) | https://en.mercopress.com/rss
            Politics | Politico Europe | https://www.politico.eu/feed/
            Defense | Defense News | https://www.defensenews.com/arc/outboundfeeds/rss/
            Defense | Breaking Defense | https://breakingdefense.com/feed/
            Defense | SpaceNews | https://spacenews.com/feed/
            """;

    static final String V18_MARKER = "# ---------- Added in v1.8: Defense & Space desk ----------";

    /** New in v1.8 (ANL-F14). Appended once to an existing feeds.txt. */
    static final String V18_ADDITIONS = "\n" + V18_MARKER + "\n" + """
            Defense | Army Times | https://www.armytimes.com/feed/
            Defense | NASA News Releases | https://www.nasa.gov/news-release/feed/
            Defense | NASA Marshall | https://www.nasa.gov/centers-and-facilities/marshall/feed/
            Defense | WHNT News 19 (Huntsville) | https://whnt.com/feed/
            """;

    /** In v1.8 these move from World/Tech to the new Defense & Space desk. */
    static final java.util.List<String> TO_DEFENSE = java.util.List.of("Defense News", "Breaking Defense", "SpaceNews");

    public static final String DEFAULT_FEEDS = """
            # The Linked Desk feed list
            # Format:  Category | Source name | Feed URL
            # Categories: World, Finance, Politics, Society, Tech, Defense
            # Add or remove as many lines as you like. Every article in every feed is downloaded.
            # A source name starting with "Google News" takes the outlet name from each headline.

            # ---------- World ----------
            World | BBC World | https://feeds.bbci.co.uk/news/world/rss.xml
            World | BBC Africa | https://feeds.bbci.co.uk/news/world/africa/rss.xml
            World | BBC Asia | https://feeds.bbci.co.uk/news/world/asia/rss.xml
            World | BBC Europe | https://feeds.bbci.co.uk/news/world/europe/rss.xml
            World | BBC Latin America | https://feeds.bbci.co.uk/news/world/latin_america/rss.xml
            World | BBC Middle East | https://feeds.bbci.co.uk/news/world/middle_east/rss.xml
            World | BBC US & Canada | https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml
            World | Al Jazeera | https://www.aljazeera.com/xml/rss/all.xml
            World | NPR World | https://feeds.npr.org/1004/rss.xml
            World | The Guardian World | https://www.theguardian.com/world/rss
            World | Deutsche Welle | https://rss.dw.com/rdf/rss-en-all
            World | France 24 | https://www.france24.com/en/rss
            World | NYT World | https://rss.nytimes.com/services/xml/rss/nyt/World.xml
            World | CBC World | https://www.cbc.ca/webfeed/rss/rss-world
            World | ABC Australia | https://www.abc.net.au/news/feed/2942460/rss.xml
            World | Times of India World | https://timesofindia.indiatimes.com/rssfeeds/296589292.cms
            World | South China Morning Post | https://www.scmp.com/rss/91/feed
            World | Japan Times | https://www.japantimes.co.jp/feed/
            World | Euronews | https://www.euronews.com/rss?level=theme&name=news
            World | Google News World | https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en

            # ---------- Finance ----------
            Finance | BBC Business | https://feeds.bbci.co.uk/news/business/rss.xml
            Finance | CNBC Top News | https://www.cnbc.com/id/100003114/device/rss/rss.html
            Finance | CNBC World Markets | https://www.cnbc.com/id/100727362/device/rss/rss.html
            Finance | MarketWatch | https://feeds.content.dowjones.io/public/rss/mw_topstories
            Finance | NYT Business | https://rss.nytimes.com/services/xml/rss/nyt/Business.xml
            Finance | The Guardian Business | https://www.theguardian.com/business/rss
            Finance | NPR Business | https://feeds.npr.org/1006/rss.xml
            Finance | Google News Business | https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=en-US&gl=US&ceid=US:en

            # ---------- Politics ----------
            Politics | NPR Politics | https://feeds.npr.org/1014/rss.xml
            Politics | Politico | https://rss.politico.com/politics-news.xml
            Politics | The Hill | https://thehill.com/news/feed/
            Politics | NYT Politics | https://rss.nytimes.com/services/xml/rss/nyt/Politics.xml
            Politics | The Guardian US Politics | https://www.theguardian.com/us-news/us-politics/rss
            Politics | BBC UK Politics | https://feeds.bbci.co.uk/news/politics/rss.xml
            Politics | Google News US | https://news.google.com/rss/headlines/section/topic/NATION?hl=en-US&gl=US&ceid=US:en

            # ---------- Society & Climate ----------
            Society | The Guardian Society | https://www.theguardian.com/society/rss
            Society | The Guardian Environment | https://www.theguardian.com/environment/rss
            Society | BBC Health | https://feeds.bbci.co.uk/news/health/rss.xml
            Society | BBC Science & Environment | https://feeds.bbci.co.uk/news/science_and_environment/rss.xml
            Society | BBC Education | https://feeds.bbci.co.uk/news/education/rss.xml
            Society | NYT Climate | https://rss.nytimes.com/services/xml/rss/nyt/Climate.xml
            Society | NPR Health | https://feeds.npr.org/1128/rss.xml
            Society | Google News Health | https://news.google.com/rss/headlines/section/topic/HEALTH?hl=en-US&gl=US&ceid=US:en

            # ---------- Tech & Science ----------
            Tech | BBC Technology | https://feeds.bbci.co.uk/news/technology/rss.xml
            Tech | NYT Technology | https://rss.nytimes.com/services/xml/rss/nyt/Technology.xml
            Tech | The Guardian Technology | https://www.theguardian.com/technology/rss
            Tech | Google News Technology | https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-US&gl=US&ceid=US:en
            Tech | Google News Science | https://news.google.com/rss/headlines/section/topic/SCIENCE?hl=en-US&gl=US&ceid=US:en
            """ + V11_ADDITIONS + V18_ADDITIONS;



    /** Removes the feed with this address from feeds.txt, keeping comments and other lines. */
    public static boolean removeFeed(String url) throws IOException {
        return rewrite(url, null);
    }

    /** Changes a feed's address in feeds.txt. */
    public static boolean changeUrl(String oldUrl, String newUrl) throws IOException {
        return rewrite(oldUrl, newUrl.trim());
    }

    private static boolean rewrite(String url, String replacement) throws IOException {
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (String line : rawText().split("\\R", -1)) {
            String[] parts = line.split("\\|");
            boolean match = !line.strip().startsWith("#") && parts.length >= 3 && parts[2].strip().equals(url);
            if (match) {
                changed = true;
                if (replacement == null) continue;
                line = parts[0].strip() + " | " + parts[1].strip() + " | " + replacement;
            }
            out.append(line).append('\n');
        }
        if (changed) saveRawText(out.toString().replaceAll("\n+$", "\n"));
        return changed;
    }
}
