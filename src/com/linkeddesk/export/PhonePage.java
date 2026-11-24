package com.linkeddesk.export;

import com.linkeddesk.ai.Cite;
import com.linkeddesk.ai.Digest;
import com.linkeddesk.alerts.EmailDigest;
import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;

/**
 * The briefing for your phone (UI-F12): one self-contained web page, sized for a phone, light or dark like the phone,
 * written into a folder your phone can see (OneDrive, Dropbox, iCloud Drive…). No server, nothing public: the page
 * is only where you put it. Earlier days are kept next to it.
 */
public final class PhonePage {
    private PhonePage() {}

    public static final String LATEST = "The Linked Desk briefing.html";
    public static final String DAYS_DIR = "briefings";
    public static final int KEEP_DAYS = 14;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneId.systemDefault());

    /** What's new on your watchlist, by watch name. */
    public static String build(Briefing b, Digest d, Regions regions, Map<String, List<StoryCluster>> watchNew, List<LocalDate> earlier, boolean inDaysFolder) {
        return build(b, d, regions, watchNew, earlier, inDaysFolder, List.of());
    }

    /** With the explainers made recently (AI-F08): each one's bottom line, with the rest folded away. */
    public static String build(Briefing b, Digest d, Regions regions, Map<String, List<StoryCluster>> watchNew, List<LocalDate> earlier, boolean inDaysFolder,
                               List<com.linkeddesk.ai.Explainer.Result> explainers) {
        StringBuilder h = new StringBuilder("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<meta name=\"color-scheme\" content=\"light dark\">\n"
                + "<title>Briefing · " + esc(DATE.format(Instant.ofEpochMilli(b.now()))) + "</title>\n<style>\n" + CSS + "</style></head><body>\n");
        h.append("<header><h1>Briefing</h1><div class=\"date\">").append(esc(DATE.format(Instant.ofEpochMilli(b.now())))).append("</div>")
         .append("<div class=\"meta\">").append(String.format("%,d articles · %d outlets · updated %s", b.articles(), b.outlets(), TIME.format(Instant.ofEpochMilli(b.now()))))
         .append("</div></header>\n<nav>");
        if (!watchNew.isEmpty()) h.append("<a href=\"#watch\">Watchlist</a>");
        if (d != null && !d.isEmpty()) h.append("<a href=\"#today\">Today</a>");
        if (!explainers.isEmpty()) h.append("<a href=\"#explainers\">Explainers</a>");
        if (!b.newLinks().isEmpty()) h.append("<a href=\"#links\">Links</a>");
        for (var e : b.desks().entrySet()) if (!e.getValue().isEmpty()) h.append("<a href=\"#").append(e.getKey().name().toLowerCase(Locale.ROOT)).append("\">").append(esc(short_(e.getKey()))).append("</a>");
        h.append("</nav>\n<main>\n");

        if (!watchNew.isEmpty()) {
            h.append("<section id=\"watch\"><h2>New on your watchlist</h2>");
            for (var e : watchNew.entrySet()) {
                h.append("<h3>").append(esc(e.getKey())).append(" <span class=\"n\">").append(e.getValue().size()).append("</span></h3>");
                for (StoryCluster c : e.getValue().subList(0, Math.min(5, e.getValue().size()))) card(h, c, regions, null);
            }
            h.append("</section>\n");
        }
        if (d != null && !d.isEmpty()) {
            h.append("<section id=\"today\"><h2>How today connects</h2><p class=\"note\">Written by Claude from the day's top stories.</p>");
            digest(h, "What the reports say", d.reported(), d, b, false);
            digest(h, "Claude's reading (inference, not reported fact)", d.inference(), d, b, true);
            digest(h, "What to watch", d.watch(), d, b, false);
            h.append("</section>\n");
        }
        if (!explainers.isEmpty()) {
            h.append("<section id=\"explainers\"><h2>Explainers</h2><p class=\"note\">Written by Claude from the articles cited.</p>");
            for (var x : explainers) {
                h.append("<h3>").append(esc(x.subject())).append("</h3>");
                explainerList(h, x, "BOTTOM LINE", null);
                h.append("<details><summary>More</summary>");
                for (String s : List.of("CHANGED", "TIMELINE", "WHY IT MATTERS", "WATCH")) explainerList(h, x, s, com.linkeddesk.ai.Explainer.TITLES.get(s));
                h.append("</details>");
            }
            h.append("</section>\n");
        }
        if (!b.newLinks().isEmpty()) {
            h.append("<section id=\"links\"><h2>Newly connected across desks</h2>");
            for (StoryLink l : b.newLinks()) {
                h.append("<div class=\"link\"><a href=\"").append(esc(EmailDigest.lead(l.from()).url)).append("\">").append(esc(l.from().headline)).append("</a> <span class=\"desk\">")
                 .append(esc(l.from().category.label())).append("</span><div class=\"arrow\">↔</div><a href=\"").append(esc(EmailDigest.lead(l.to()).url)).append("\">")
                 .append(esc(l.to().headline)).append("</a> <span class=\"desk\">").append(esc(l.to().category.label())).append("</span><div class=\"meta\">Shared: ")
                 .append(esc(String.join(", ", l.sharedTerms()))).append("</div></div>");
            }
            h.append("</section>\n");
        }
        for (var e : b.desks().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            h.append("<section id=\"").append(e.getKey().name().toLowerCase(Locale.ROOT)).append("\"><h2 style=\"border-color:").append(e.getKey().hex()).append("\">")
             .append(esc(e.getKey().label())).append("</h2>");
            for (Briefing.Ranked r : e.getValue()) card(h, r.story(), regions, e.getKey().hex());
            h.append("</section>\n");
        }
        if (!earlier.isEmpty()) {
            h.append("<section><h2>Earlier briefings</h2><ul class=\"days\">");
            DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
            for (LocalDate day : earlier) h.append("<li><a href=\"").append(inDaysFolder ? "" : DAYS_DIR + "/").append(day).append(".html\">").append(f.format(day)).append("</a></li>");
            h.append("</ul></section>\n");
        }
        h.append("</main><footer>").append(inDaysFolder ? "<a href=\"../" + esc(LATEST) + "\">Latest briefing</a> · " : "")
         .append("Made by The Linked Desk on your PC. Headlines open the articles.</footer>\n</body></html>\n");
        return h.toString();
    }

    private static void card(StringBuilder h, StoryCluster c, Regions regions, String color) {
        Article lead = EmailDigest.lead(c);
        Regions.Spread sp = regions.spread(c);
        h.append("<article").append(color == null ? " style=\"border-left-color:" + c.category.hex() + "\"" : " style=\"border-left-color:" + color + "\"").append(">")
         .append("<a class=\"hl\" href=\"").append(esc(lead.url)).append("\">").append(esc(c.headline)).append("</a>")
         .append("<div class=\"meta\">").append(esc(sp.singleSource() ? "Single source: " + lead.source : sp.label())).append(lead.kindLabel().isEmpty() ? "" : " · " + lead.kindLabel()).append("</div>");
        if (!c.summary.isEmpty()) h.append("<p>").append(esc(c.summary.length() > 260 ? c.summary.substring(0, 258) + "…" : c.summary)).append("</p>");
        List<Article> others = new ArrayList<>();
        Set<String> outlets = new HashSet<>(Set.of(lead.source));
        for (Article a : c.articlesNewestFirst()) if (a != lead && outlets.add(a.source) && EmailDigest.lead(c) != a) others.add(a);
        if (!others.isEmpty()) {
            h.append("<details><summary>").append(others.size()).append(others.size() == 1 ? " more outlet" : " more outlets").append("</summary><ul>");
            for (Article a : others) h.append("<li><a href=\"").append(esc(a.url)).append("\">").append(esc(a.title)).append("</a> <span class=\"src\">").append(esc(a.source)).append("</span></li>");
            h.append("</ul></details>");
        }
        h.append("</article>\n");
    }

    private static void digest(StringBuilder h, String title, List<String> lines, Digest d, Briefing b, boolean italic) {
        if (lines.isEmpty()) return;
        h.append("<h3>").append(esc(title)).append("</h3><ul class=\"dg\">");
        for (String l : lines) {
            Matcher m = Cite.TAG.matcher(esc(l));
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String id = "S" + m.group(1);
                StoryCluster c = d.refs().containsKey(id) ? Cite.find(d.refs().get(id), b.result().stories()) : null;
                m.appendReplacement(sb, Matcher.quoteReplacement(c == null ? "" : "<a class=\"cite\" href=\"" + esc(EmailDigest.lead(c).url) + "\">" + esc(EmailDigest.lead(c).source) + "</a>"));
            }
            m.appendTail(sb);
            h.append("<li>").append(italic ? "<i>" : "").append(sb).append(italic ? "</i>" : "").append("</li>");
        }
        h.append("</ul>");
    }

    private static void explainerList(StringBuilder h, com.linkeddesk.ai.Explainer.Result x, String section, String title) {
        List<String> lines = x.sections().getOrDefault(section, List.of());
        if (lines.isEmpty()) return;
        if (title != null) h.append("<h4>").append(esc(title)).append("</h4>");
        h.append("<ul class=\"dg\">");
        for (String l : lines) h.append("<li>").append(com.linkeddesk.ai.Explainer.render(esc(l), x.refs(),
                s -> "<a class=\"cite\" href=\"" + esc(s.url()) + "\">" + esc(s.source()) + "</a>")).append("</li>");
        h.append("</ul>");
    }

    private static String short_(Category c) {
        return switch (c) { case SOCIETY -> "Society"; case TECH -> "Tech"; case DEFENSE -> "Defense"; default -> c.label(); };
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** Writes today's page (and a dated copy), keeps the last two weeks, removes older days. Returns the page written. */
    public static Path write(Path folder, LocalDate today, java.util.function.BiFunction<List<LocalDate>, Boolean, String> page) throws IOException {
        Path days = folder.resolve(DAYS_DIR);
        Files.createDirectories(days);
        List<LocalDate> have = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(days, "*.html")) {
            for (Path p : ds) try { have.add(LocalDate.parse(p.getFileName().toString().replace(".html", ""))); } catch (RuntimeException ignored) { }
        }
        have.remove(today);
        have.sort(Comparator.reverseOrder());
        for (int i = KEEP_DAYS - 1; i < have.size(); i++) Files.deleteIfExists(days.resolve(have.get(i) + ".html"));
        List<LocalDate> earlier = new ArrayList<>(have.subList(0, Math.min(have.size(), KEEP_DAYS - 1)));
        Path latest = folder.resolve(LATEST);
        atomic(latest, page.apply(earlier, false));
        atomic(days.resolve(today + ".html"), page.apply(earlier, true));
        return latest;
    }

    private static void atomic(Path f, String text) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final String CSS = """
            :root{--bg:#fbfaf7;--fg:#1b1b1b;--muted:#666;--card:#fff;--line:#e3e0d8;--link:#1f5fa8}
            @media (prefers-color-scheme:dark){:root{--bg:#16181b;--fg:#e8e6e1;--muted:#a3a09a;--card:#1f2226;--line:#33373c;--link:#7fb2ff}}
            *{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
            body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif}
            header{padding:18px 16px 8px}h1{font:700 28px/1.1 Georgia,serif;margin:0}.date{font-size:18px;margin-top:2px}
            .meta{color:var(--muted);font-size:14px;margin-top:2px}
            nav{position:sticky;top:0;background:var(--bg);display:flex;gap:6px;overflow-x:auto;padding:8px 12px;border-bottom:1px solid var(--line);z-index:2}
            nav a{flex:none;padding:6px 12px;border:1px solid var(--line);border-radius:16px;color:var(--fg);text-decoration:none;font-size:14px}
            main{padding:4px 12px 24px;max-width:720px;margin:0 auto}
            h2{font:700 20px Georgia,serif;margin:22px 4px 8px;padding-left:8px;border-left:5px solid var(--line)}
            h3{font-size:16px;margin:14px 4px 6px}.n{background:#b26a00;color:#fff;border-radius:10px;padding:1px 7px;font-size:13px}
            article{background:var(--card);border:1px solid var(--line);border-left:5px solid var(--line);border-radius:10px;padding:12px 14px;margin:10px 0}
            a{color:var(--link)}a.hl{display:block;font-weight:600;font-size:18px;text-decoration:none;color:var(--fg);line-height:1.3}
            article p{margin:6px 0 0;font-size:15px}details{margin-top:8px;font-size:15px}summary{color:var(--link);cursor:pointer;padding:4px 0}
            details ul{padding-left:18px;margin:4px 0}details li{margin:6px 0}.src,.desk{color:var(--muted);font-size:13px}
            .link{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px;margin:10px 0}.arrow{color:var(--muted);margin:2px 0}
            .note{color:var(--muted);font-size:14px;margin:0 4px}ul.dg{padding-left:20px}ul.dg li{margin:6px 0}a.cite{font-size:13px}
            ul.days{list-style:none;padding:0;display:flex;flex-wrap:wrap;gap:8px}ul.days a{display:block;padding:8px 12px;border:1px solid var(--line);border-radius:8px;text-decoration:none}
            footer{color:var(--muted);font-size:13px;text-align:center;padding:16px}
            """;
}
