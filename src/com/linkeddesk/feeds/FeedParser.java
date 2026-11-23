package com.linkeddesk.feeds;

import com.linkeddesk.model.Article;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses RSS 2.0, RSS 1.0 (RDF) and Atom documents into articles, using only the JDK. */
public final class FeedParser {
    private static final int MAX_SUMMARY = 1200;

    private FeedParser() {}

    public static List<Article> parse(byte[] xml, FeedSource feed, long fetchedAt) throws Exception {
        Document doc = builder().parse(new ByteArrayInputStream(stripBom(xml)));
        List<Article> out = new ArrayList<>();
        collect(doc.getElementsByTagNameNS("*", "item"), feed, fetchedAt, out);
        collect(doc.getElementsByTagNameNS("*", "entry"), feed, fetchedAt, out);
        return out;
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b;
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean v) {
        try { f.setFeature(feature, v); } catch (Exception ignored) { }
    }

    /** Full text longer than this is cut (it's for reading and searching, not an archive of the web). */
    static final int MAX_FULL_TEXT = 30_000;

    private static void collect(NodeList items, FeedSource feed, long fetchedAt, List<Article> out) {
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String title = clean(child(e, "title"));
            String link = link(e);
            Element enclosure = childEl(e, "enclosure");
            String encType = enclosure == null ? "" : enclosure.getAttribute("type").toLowerCase(Locale.ROOT);
            if (link.isEmpty() && enclosure != null) link = enclosure.getAttribute("url").trim();     // a podcast episode without a page
            if (link.isEmpty()) continue;
            String rawSummary = firstNonEmpty(child(e, "description"), child(e, "summary"), child(e, "encoded"), child(e, "content"));
            Element media = childEl(e, "group");                                                        // YouTube: media:group/media:description
            if (rawSummary.isBlank() && media != null) rawSummary = child(media, "description");
            if (rawSummary.isBlank()) rawSummary = child(e, "subtitle");                                 // itunes:subtitle
            String summary = clean(rawSummary);
            // What kind of item: a video, a podcast episode, a social post, or an ordinary article (FET-F06).
            String kind = "";
            String lowLink = link.toLowerCase(Locale.ROOT);
            if (!child(e, "videoId").isBlank() || lowLink.contains("youtube.com/watch") || lowLink.contains("youtube.com/shorts") || encType.startsWith("video/")) kind = "video";
            else if (encType.startsWith("audio/") || !child(e, "duration").isBlank()) kind = "audio";
            else if (title.isEmpty() || lowLink.contains("reddit.com/r/") || lowLink.contains("bsky.app/profile/") || lowLink.matches("https?://[^/]+/@[^/]+/\\d+.*")) kind = "post";
            if (title.isEmpty()) {
                // Social posts have no headline: use the start of the post.
                if (summary.isEmpty()) continue;
                title = firstSentence(summary, 110);
                if (summary.equals(title)) summary = "";
            }
            if (kind.equals("post") && lowLink.contains("reddit.com/r/")) summary = summary.replaceFirst("\\s*submitted by .*$", "").trim();
            // The full text, when the publisher puts it in the feed (content:encoded, Atom content) (FET-F07).
            String full = paragraphs(firstNonEmpty(child(e, "encoded"), child(e, "content")));
            if (full.length() > MAX_FULL_TEXT) full = full.substring(0, MAX_FULL_TEXT).trim() + "…";
            if (summary.length() > MAX_SUMMARY) summary = summary.substring(0, MAX_SUMMARY).trim() + "…";
            if (summary.equalsIgnoreCase(title)) summary = "";
            if (full.length() < 600 || full.replaceAll("\\s+", " ").length() < summary.length() * 3 / 2) full = "";   // only when it really is more
            long published = parseDate(firstNonEmpty(child(e, "pubDate"), child(e, "published"),
                    child(e, "updated"), child(e, "date")), fetchedAt);

            String source = feed.name();
            if (feed.outletPerItem()) {
                String itemSource = clean(child(e, "source"));          // Google: <source>, Bing: <News:Source>
                int dash = title.lastIndexOf(" - ");
                if (dash > 0 && dash > title.length() - 60) {
                    if (itemSource.isEmpty()) itemSource = title.substring(dash + 3).trim();
                    if (itemSource.equalsIgnoreCase(title.substring(dash + 3).trim())) title = title.substring(0, dash).trim();
                }
                itemSource = itemSource.replaceFirst("\\s+on MSN$", "");      // Bing: "CNBC on MSN" -> "CNBC"
                if (!itemSource.isEmpty()) source = itemSource;
                if (summary.startsWith(title)) summary = summary.substring(title.length()).replaceFirst("^\\s*[-–—]?\\s*" + Pattern.quote(source) + "\\s*$", "").trim();
                link = unwrapRedirect(link);
            }
            out.add(new Article(idFor(link), link, title, summary, source, feed.category(), published, fetchedAt, kind, full));
        }
    }

    /** The first sentence (or the first {@code max} characters, cut at a word). */
    static String firstSentence(String s, int max) {
        java.util.regex.Matcher m = Pattern.compile("^(.{20,}?[.!?])(\\s|$)").matcher(s);
        String t = m.find() && m.group(1).length() <= max ? m.group(1) : s;
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max - 1);
        return t.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
    }

    private static final Pattern BREAKS = Pattern.compile("(?i)<\\s*(/p|br\\s*/?|/h[1-6]|/li|/blockquote|/div)\\s*>");

    /** Text with its paragraphs kept (blank line between them), tags and entities removed. */
    public static String paragraphs(String html) {
        if (html == null || html.isBlank()) return "";
        String t = html.contains("&lt;") && !html.contains("<") ? unescape(html) : html;
        t = t.replaceAll("(?is)<(script|style|figure|figcaption)[^>]*>.*?</\\1>", " ");
        t = BREAKS.matcher(t).replaceAll("\n\n");
        t = TAGS.matcher(t).replaceAll(" ");
        t = unescape(t);
        StringBuilder sb = new StringBuilder();
        for (String para : t.split("\n\\s*\n")) {
            String p = para.replaceAll("[\\s\\u00A0]+", " ").trim();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p);
        }
        return sb.toString();
    }

    private static Element childEl(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && localName.equals(c.getLocalName())) return c;
        return null;
    }

    /** Bing News links point at a click-tracking address that carries the real one in url=. */
    static String unwrapRedirect(String link) {
        Matcher m = Pattern.compile("[?&]url=([^&]+)").matcher(link);
        if (link.contains("bing.com/news/apiclick") && m.find()) {
            try { return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8); } catch (RuntimeException ignored) { }
        }
        return link;
    }

    private static String child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && localName.equals(c.getLocalName())) return c.getTextContent();
        }
        if (localName.equals("source")) {          // Bing News uses <News:Source>
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element c && "Source".equals(c.getLocalName())) return c.getTextContent();
            }
        }
        return "";
    }

    private static String link(Element e) {
        String alt = "";
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && "link".equals(c.getLocalName())) {
                String href = c.getAttribute("href");
                if (!href.isEmpty()) {                       // Atom
                    String rel = c.getAttribute("rel");
                    if (rel.isEmpty() || rel.equals("alternate")) return href.trim();
                    if (alt.isEmpty()) alt = href.trim();
                } else if (!c.getTextContent().isBlank()) { // RSS
                    return c.getTextContent().trim();
                }
            }
        }
        if (!alt.isEmpty()) return alt;
        String guid = child(e, "guid").trim();
        return guid.startsWith("http") ? guid : "";
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static byte[] stripBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            byte[] c = new byte[b.length - 3];
            System.arraycopy(b, 3, c, 0, c.length);
            return c;
        }
        int i = 0;
        while (i < b.length && Character.isWhitespace(b[i])) i++;
        if (i == 0) return b;
        byte[] c = new byte[b.length - i];
        System.arraycopy(b, i, c, 0, c.length);
        return c;
    }

    private static final Pattern TRACKING = Pattern.compile("(?i)(utm_.*|at_.*|ns_.*|cmpid|ocid|taid|fbclid|gclid|mc_cid|mc_eid|ref|src|smid|cmp|traffic_source)");

    /** Stable id from the URL, ignoring tracking parameters, fragments, http/https and www. */
    public static String idFor(String url) {
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?');
        if (q >= 0) {
            StringBuilder kept = new StringBuilder();
            for (String part : u.substring(q + 1).split("&")) {
                if (part.isEmpty()) continue;
                String key = part.split("=", 2)[0];
                if (TRACKING.matcher(key).matches()) continue;
                kept.append(kept.length() == 0 ? "?" : "&").append(part);
            }
            u = u.substring(0, q) + kept;
        }
        u = u.replaceFirst("^https?://(www\\.)?", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(u.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (Exception e) {
            return Integer.toHexString(u.hashCode());
        }
    }

    // ---------------------------------------------------------------- text cleaning

    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    /** Removes HTML tags, decodes entities and collapses whitespace. */
    public static String clean(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s;
        if (t.contains("&lt;")) t = unescape(t);   // double-escaped HTML
        t = TAGS.matcher(t).replaceAll(" ");
        t = unescape(t);
        return t.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group(1);
            String rep;
            try {
                if (g.startsWith("#x")) rep = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                else if (g.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                else rep = switch (g) {
                    case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\"";
                    case "apos" -> "'"; case "nbsp" -> " "; case "rsquo", "lsquo" -> "'";
                    case "rdquo", "ldquo" -> "\""; case "mdash" -> "—"; case "ndash" -> "–";
                    case "hellip" -> "…"; case "pound" -> "£"; case "euro" -> "€";
                    default -> m.group();
                };
            } catch (RuntimeException ex) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- dates

    private static final String[][] ZONES = {
            {" GMT", " +0000"}, {" UTC", " +0000"}, {" UT", " +0000"}, {" Z", " +0000"},
            {" EST", " -0500"}, {" EDT", " -0400"}, {" CST", " -0600"}, {" CDT", " -0500"},
            {" MST", " -0700"}, {" MDT", " -0600"}, {" PST", " -0800"}, {" PDT", " -0700"},
            {" BST", " +0100"}, {" CET", " +0100"}, {" CEST", " +0200"}, {" IST", " +0530"},
            {" JST", " +0900"}, {" AEST", " +1000"}, {" AEDT", " +1100"}};

    private static final DateTimeFormatter RFC_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] Z", Locale.ENGLISH);
    private static final DateTimeFormatter RFC_DOW =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] Z", Locale.ENGLISH);

    public static long parseDate(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim().replaceAll("\\s+", " ");
        long v = -1;
        try { v = OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        if (v < 0) try { v = Instant.parse(s).toEpochMilli(); } catch (Exception ignored) { }
        if (v < 0) try { v = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignored) { }
        if (v < 0) {
            String r = s;
            for (String[] z : ZONES) if (r.endsWith(z[0])) { r = r.substring(0, r.length() - z[0].length()) + z[1]; break; }
            r = r.replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
            try { v = ZonedDateTime.parse(r, RFC_DOW).toInstant().toEpochMilli(); } catch (Exception ignored) { }
            if (v < 0) {
                String noDow = r.replaceFirst("^[A-Za-z]{3,9},? ", "");
                try { v = ZonedDateTime.parse(noDow, RFC_NO_DOW).toInstant().toEpochMilli(); } catch (Exception ignored) { }
            }
        }
        if (v < 0) return fallback;
        return Math.min(v, fallback + 3_600_000L); // never trust dates far in the future
    }
}
