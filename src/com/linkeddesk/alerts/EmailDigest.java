package com.linkeddesk.alerts;

import com.linkeddesk.ai.Cite;
import com.linkeddesk.ai.Digest;
import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The briefing as an email (ALR-F04): Claude's digest (if written), new links between desks and the top stories
 * per desk. Every headline links to the article on the outlet's site, and each story lists its other outlets as
 * links, so everything works from a phone. A plain-text version is included for mail apps that don't show HTML.
 */
public final class EmailDigest {
    private EmailDigest() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d").withZone(ZoneId.systemDefault());
    private static final String LINK = "color:#1f5fa8;text-decoration:none";

    public record Email(String subject, String text, String html) {}

    /** The article a story's headline links to: the one with that headline, else the newest. */
    public static Article lead(StoryCluster c) {
        for (Article a : c.articles) if (a.title.equals(c.headline) && web(a.url)) return a;
        for (Article a : c.articlesNewestFirst()) if (web(a.url)) return a;
        return c.articles.get(0);
    }

    static boolean web(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://")); }

    public static Email build(Briefing b, Digest d, Regions regions) {
        String date = DATE.format(Instant.ofEpochMilli(b.now()));
        String subject = "The Linked Desk · " + date + ": " + b.stories() + " stories from " + b.outlets() + " outlets";
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html><body style=\"margin:0;padding:16px;font-family:Segoe UI,Arial,sans-serif;"
                + "font-size:15px;color:#1b1b1b;background:#ffffff;max-width:720px\">");
        StringBuilder t = new StringBuilder();
        h.append("<h1 style=\"font-family:Georgia,serif;font-size:24px;margin:0 0 4px 0\">Your briefing · ").append(esc(date)).append("</h1>")
         .append("<p style=\"color:#666;font-size:13px;margin:0 0 14px 0\">").append(String.format("%,d", b.articles())).append(" articles from ")
         .append(b.outlets()).append(" outlets in the last 24 hours. Headlines open the article.</p>");
        t.append("YOUR BRIEFING · ").append(date).append("\n").append(b.articles()).append(" articles from ").append(b.outlets()).append(" outlets in the last 24 hours.\n\n");

        if (d != null && !d.isEmpty()) {
            h.append(h2("How today connects <span style=\"font-size:12px;color:#777\">· written by Claude</span>"));
            t.append("HOW TODAY CONNECTS (written by Claude)\n");
            section(h, t, "What the reports say", d.reported(), d, b, false);
            section(h, t, "Claude's reading (inference, not reported fact)", d.inference(), d, b, true);
            section(h, t, "What to watch", d.watch(), d, b, false);
        }
        if (!b.newLinks().isEmpty()) {
            h.append(h2("Newly connected across desks"));
            t.append("NEWLY CONNECTED ACROSS DESKS\n");
            for (StoryLink l : b.newLinks()) {
                Article a = lead(l.from()), c = lead(l.to());
                h.append("<p style=\"margin:6px 0\">").append(a(a.url, l.from().headline)).append(" <span style=\"color:#777\">(").append(esc(l.from().category.label()))
                 .append(")</span><br>↔ ").append(a(c.url, l.to().headline)).append(" <span style=\"color:#777\">(").append(esc(l.to().category.label()))
                 .append(")</span><br><span style=\"color:#555;font-size:13px\">Shared: ").append(esc(String.join(", ", l.sharedTerms()))).append("</span></p>");
                t.append("- ").append(l.from().headline).append("\n  ").append(a.url).append("\n  <-> ").append(l.to().headline).append("\n  ").append(c.url).append('\n');
            }
            t.append('\n');
        }
        for (var e : b.desks().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            h.append("<h2 style=\"font-family:Georgia,serif;font-size:18px;margin:18px 0 6px 0;color:").append(e.getKey().hex()).append("\">")
             .append(esc(e.getKey().label())).append("</h2>");
            t.append(e.getKey().label().toUpperCase()).append('\n');
            int i = 0;
            for (var r : e.getValue()) {
                StoryCluster c = r.story();
                Article lead = lead(c);
                var sp = regions.spread(c);
                h.append("<p style=\"margin:8px 0\"><b>").append(++i).append(".</b> ").append(a(lead.url, c.headline)).append("<br>")
                 .append("<span style=\"color:#666;font-size:13px\">").append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources")
                 .append(" · ").append(esc(sp.singleSource() ? "single source" : sp.label())).append("</span>");
                List<String> others = new ArrayList<>();
                for (Article a : c.articlesNewestFirst()) {
                    if (a == lead || !web(a.url) || others.size() >= 4) continue;
                    if (others.stream().anyMatch(o -> o.contains(">" + esc(a.source) + "<"))) continue;
                    others.add(a(a.url, a.source));
                }
                if (!others.isEmpty()) h.append("<br><span style=\"font-size:13px\">Also: ").append(String.join(" · ", others)).append("</span>");
                h.append("</p>");
                t.append(i).append(". ").append(c.headline).append(" (").append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources").append(")\n   ").append(lead.url).append('\n');
            }
            t.append('\n');
        }
        h.append("<p style=\"color:#888;font-size:12px;margin-top:20px\">Sent by The Linked Desk on your PC. To stop these emails, untick "
                + "\"Email me the briefing\" in Tools &gt; Settings.</p></body></html>");
        t.append("Sent by The Linked Desk on your PC. To stop these emails, untick \"Email me the briefing\" in Tools > Settings.\n");
        return new Email(subject, t.toString(), h.toString());
    }

    private static void section(StringBuilder h, StringBuilder t, String title, List<String> lines, Digest d, Briefing b, boolean italic) {
        if (lines.isEmpty()) return;
        h.append("<p style=\"margin:8px 0 2px 0;font-size:13px;color:#555\"><b>").append(esc(title)).append("</b></p><ul style=\"margin:0 0 6px 18px;padding:0\">");
        t.append(title).append(":\n");
        for (String l : lines) {
            h.append("<li style=\"margin:3px 0\">").append(italic ? "<i>" : "").append(cite(l, d, b, true)).append(italic ? "</i>" : "").append("</li>");
            t.append("- ").append(cite(l, d, b, false)).append('\n');
        }
        h.append("</ul>");
        t.append('\n');
    }

    /** [S3] becomes a link to that story's article (HTML) or its address in brackets (text). */
    private static String cite(String line, Digest d, Briefing b, boolean html) {
        Matcher m = Cite.TAG.matcher(html ? esc(line) : line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = "S" + m.group(1);
            StoryCluster c = d.refs().containsKey(id) ? Cite.find(d.refs().get(id), b.result().stories()) : null;
            String rep;
            if (c == null) rep = "";
            else if (html) rep = "<a href=\"" + esc(lead(c).url) + "\" style=\"" + LINK + ";font-size:12px\">[" + esc(lead(c).source) + "]</a>";
            else rep = "[" + lead(c).source + ": " + lead(c).url + "]";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String h2(String inner) {
        return "<h2 style=\"font-family:Georgia,serif;font-size:18px;margin:18px 0 6px 0;border-bottom:1px solid #ddd\">" + inner + "</h2>";
    }

    private static String a(String url, String text) {
        if (!web(url)) return esc(text);
        return "<a href=\"" + esc(url) + "\" style=\"" + LINK + "\">" + esc(text) + "</a>";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
