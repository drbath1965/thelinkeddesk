package com.linkeddesk.feeds;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the link you'd copy from a browser into the feed behind it (FET-F06): a YouTube channel, handle or
 * playlist; an Apple Podcasts show; a Mastodon or Bluesky account; a subreddit. Anything else is taken as a feed
 * address as it is.
 */
public final class FeedLinks {
    private FeedLinks() {}

    /** What a link turned into: the feed address, what kind of source it is, and a name if one was found. */
    public record Result(String feedUrl, String kind, String name) {}

    /** Downloads a page as text (for the few cases that need to look the feed up). */
    public interface Fetch { String get(String url) throws IOException; }

    private static final Pattern YT_CHANNEL = Pattern.compile("youtube\\.com/channel/(UC[\\w-]{20,24})");
    private static final Pattern YT_PLAYLIST = Pattern.compile("youtube\\.com/(?:playlist|watch)\\?.*?list=([\\w-]+)");
    private static final Pattern YT_HANDLE = Pattern.compile("youtube\\.com/(@[\\w.-]+|c/[\\w.-]+|user/[\\w.-]+)");
    private static final Pattern YT_ID_IN_PAGE = Pattern.compile("(?:\"channelId\"\\s*:\\s*\"|\"externalId\"\\s*:\\s*\"|channel_id=|youtube\\.com/channel/)(UC[\\w-]{20,24})");
    private static final Pattern APPLE = Pattern.compile("podcasts\\.apple\\.com/.*?/id(\\d+)");
    private static final Pattern FEED_URL_JSON = Pattern.compile("\"feedUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME_JSON = Pattern.compile("\"collectionName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BSKY = Pattern.compile("bsky\\.app/profile/([\\w.:-]+)");
    private static final Pattern REDDIT = Pattern.compile("reddit\\.com/(r|user|u)/([\\w-]+)");
    private static final Pattern MASTODON = Pattern.compile("^(https?://[^/]+)/@([\\w.]+)(?:@([\\w.-]+))?/?$");
    private static final Pattern TITLE = Pattern.compile("(?is)<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"|<title>([^<]+)</title>");

    public static Result resolve(String input, Fetch fetch) throws IOException {
        String u = input.strip();
        if (u.isEmpty()) throw new IOException("Paste a link first.");
        if (!u.matches("(?i)https?://.*")) u = "https://" + u;
        String low = u.toLowerCase(Locale.ROOT);
        Matcher m;
        if (low.contains("youtube.com/feeds/videos.xml")) return new Result(u, "video", "");
        if ((m = YT_PLAYLIST.matcher(u)).find()) return new Result("https://www.youtube.com/feeds/videos.xml?playlist_id=" + m.group(1), "video", "");
        if ((m = YT_CHANNEL.matcher(u)).find()) return new Result("https://www.youtube.com/feeds/videos.xml?channel_id=" + m.group(1), "video", "");
        if ((m = YT_HANDLE.matcher(u)).find()) {
            String page = fetch.get("https://www.youtube.com/" + m.group(1));
            Matcher id = YT_ID_IN_PAGE.matcher(page);
            if (!id.find()) throw new IOException("Couldn't find the channel behind " + m.group(1) + ". Open the channel, choose About > Share channel > Copy channel ID, and paste youtube.com/channel/<that ID>.");
            return new Result("https://www.youtube.com/feeds/videos.xml?channel_id=" + id.group(1), "video", pageTitle(page).replaceFirst("\\s*-\\s*YouTube$", ""));
        }
        if ((m = APPLE.matcher(u)).find()) {
            String json = fetch.get("https://itunes.apple.com/lookup?id=" + m.group(1) + "&entity=podcast");
            Matcher f = FEED_URL_JSON.matcher(json);
            if (!f.find()) throw new IOException("Apple doesn't list a public feed for this podcast.");
            Matcher n = NAME_JSON.matcher(json);
            return new Result(f.group(1).replace("\\/", "/"), "audio", n.find() ? n.group(1) : "");
        }
        if ((m = BSKY.matcher(u)).find()) return new Result("https://bsky.app/profile/" + m.group(1) + "/rss", "post", "");
        if ((m = REDDIT.matcher(u)).find()) return new Result("https://www.reddit.com/" + (m.group(1).equals("r") ? "r" : "user") + "/" + m.group(2) + "/.rss", "post", "");
        if ((m = MASTODON.matcher(u)).find()) {
            // https://mastodon.social/@user, or a remote account like https://mastodon.social/@user@other.server
            String host = m.group(3) != null ? "https://" + m.group(3) : m.group(1);
            return new Result(host + "/@" + m.group(2) + ".rss", "post", "");
        }
        return new Result(u, "", "");
    }

    static String pageTitle(String html) {
        Matcher t = TITLE.matcher(html);
        if (!t.find()) return "";
        return FeedParser.clean(t.group(1) != null ? t.group(1) : t.group(2));
    }

    /** Fetches with the same browser-like identification the downloads use. */
    public static final Fetch WEB = url -> {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TheLinkedDesk/1.7 RSS reader")
                            .header("Accept-Language", "en").build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) throw new IOException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled");
        }
    };
}
