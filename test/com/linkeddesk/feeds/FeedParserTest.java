package com.linkeddesk.feeds;

import com.linkeddesk.Check;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** QA-F01: feed parsing — formats, odd dates, entities, BOM, search-feed outlets. */
public final class FeedParserTest {
    private static final long NOW = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli();

    private static List<Article> parse(String xml, FeedSource f) throws Exception {
        return FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), f, NOW);
    }

    private static long d(String raw) { return FeedParser.parseDate(raw, NOW); }

    public static void run(Check c) throws Exception {
        c.suite("Feed parser (QA-F01)");
        FeedSource bbc = new FeedSource(Category.WORLD, "BBC World", "https://x/rss");

        List<Article> rss = parse("<?xml version='1.0'?><rss version='2.0'><channel><item><title>Hello &amp; welcome</title>"
                + "<link>https://bbc.co.uk/a?utm_source=rss&amp;id=5</link><description><![CDATA[<p>Body <b>text</b> &#8220;quoted&#8221;</p>]]></description>"
                + "<pubDate>Mon, 23 Nov 2026 08:00:00 GMT</pubDate></item><item><title>No link</title></item></channel></rss>", bbc);
        c.eq(1, rss.size(), "RSS item read; item without a link skipped");
        c.eq("Hello & welcome", rss.get(0).title, "entities in titles decoded");
        c.eq("Body text “quoted”", rss.get(0).summary, "HTML stripped and numeric entities decoded in summary");
        c.eq(Instant.parse("2026-11-23T08:00:00Z").toEpochMilli(), rss.get(0).published, "RFC 822 date with GMT");
        c.eq(FeedParser.idFor("https://www.bbc.co.uk/a?id=5"), rss.get(0).id, "tracking parameters and www ignored in the id");

        List<Article> atom = parse("<feed xmlns='http://www.w3.org/2005/Atom'><entry><title type='html'>Atom &lt;b&gt;one&lt;/b&gt;</title>"
                + "<link rel='self' href='https://x/self'/><link rel='alternate' href='https://x/alt'/><summary>S</summary>"
                + "<published>2026-11-22T10:00:00-05:00</published></entry></feed>", bbc);
        c.eq("Atom one", atom.get(0).title, "Atom: double-escaped HTML in title cleaned");
        c.eq("https://x/alt", atom.get(0).url, "Atom: alternate link preferred over self");
        c.eq(Instant.parse("2026-11-22T15:00:00Z").toEpochMilli(), atom.get(0).published, "ISO date with offset");

        List<Article> rdf = parse("﻿  <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns='http://purl.org/rss/1.0/' "
                + "xmlns:dc='http://purl.org/dc/elements/1.1/'><item><title>RDF item</title><link>https://dw.com/1</link>"
                + "<dc:date>2026-11-22T12:30:00+01:00</dc:date></item></rdf:RDF>", bbc);
        c.eq(1, rdf.size(), "RDF read, with a byte-order mark and leading spaces");
        c.eq(Instant.parse("2026-11-22T11:30:00Z").toEpochMilli(), rdf.get(0).published, "dc:date read");

        c.eq(Instant.parse("2026-11-19T14:20:00Z").toEpochMilli(), d("Thu, 19 Nov 2026 09:20:00 EST"), "EST zone name");
        c.eq(Instant.parse("2026-11-19T14:20:00Z").toEpochMilli(), d("19 Nov 2026 09:20 -0500"), "no weekday, no seconds");
        c.eq(Instant.parse("2026-11-19T09:20:00Z").toEpochMilli(), d("Thu, 19 Nov 2026 09:20:00 +00:00"), "offset with a colon");
        c.eq(Instant.parse("2026-11-03T09:20:00Z").toEpochMilli(), d("Tue, 3 Nov 2026 09:20:00 Z"), "single-digit day and Z");
        c.eq(NOW, d("next Tuesday-ish"), "unreadable date falls back to download time");
        c.eq(NOW + 3_600_000L, d("Fri, 01 Jan 2027 00:00:00 GMT"), "dates far in the future are capped");

        FeedSource gnews = new FeedSource(Category.WORLD, "Google News · Hormuz", "https://news.google.com/rss/search?q=hormuz", "Hormuz");
        List<Article> g = parse("<rss><channel><item><title>Tankers hit mines in Hormuz - Reuters</title><link>https://news.google.com/rss/articles/abc</link>"
                + "<description>Tankers hit mines in Hormuz Reuters</description><source url='https://reuters.com'>Reuters</source></item></channel></rss>", gnews);
        c.eq("Reuters", g.get(0).source, "Google News: outlet from <source>");
        c.eq("Tankers hit mines in Hormuz", g.get(0).title, "Google News: ' - Outlet' removed from headline");
        c.eq("", g.get(0).summary, "Google News: summary that only repeats the headline is dropped");

        FeedSource bing = new FeedSource(Category.WORLD, "Bing News · Hormuz", "https://www.bing.com/news/search?q=hormuz&format=rss", "Hormuz");
        List<Article> b = parse("<rss xmlns:News='https://www.bing.com/news/search?q=x&amp;format=rss'><channel><item><title>Iran warns shipping</title>"
                + "<link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;url=https%3a%2f%2fapnews.com%2farticle%2firan-1&amp;c=1</link>"
                + "<description>Iran warned...</description><News:Source>AP News on MSN</News:Source></item></channel></rss>", bing);
        c.eq("AP News", b.get(0).source, "Bing News: outlet from <News:Source>, ' on MSN' removed");
        c.eq("https://apnews.com/article/iran-1", b.get(0).url, "Bing News: click-tracking link unwrapped to the real article");

        String err = "";
        try { parse("<rss><channel><item><title>broken", bbc); } catch (Exception e) { err = FeedFetcher.describe(e); }
        c.that(err.startsWith("Not a valid feed"), "broken XML reported as 'Not a valid feed'");
        String xxe = "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&x;</title><link>https://a/b</link></item></channel></rss>";
        String leaked = "";
        try { List<Article> r = parse(xxe, bbc); leaked = r.isEmpty() ? "" : r.get(0).title; } catch (Exception e) { leaked = ""; }
        c.that(!leaked.contains("root:"), "external entities are never read from disk (XXE-safe)");
    }
}
