package com.linkeddesk.export;

import com.linkeddesk.ai.Digest;
import com.linkeddesk.alerts.EmailDigest;
import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.model.*;
import com.linkeddesk.store.ArticleStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Export (EXP-F01): a story, a thread, the briefing or a list of stories as HTML (opens in any browser, pastes into
 * Word with its links), Markdown, Word or CSV (one row per article, opens in Excel), and a story with its sources for
 * the clipboard.
 */
public final class Exporter {
    private Exporter() {}

    public enum Format {
        HTML("Web page (.html)", "html"), WORD("Word document (.docx)", "docx"), MARKDOWN("Markdown (.md)", "md"), CSV("Spreadsheet for Excel (.csv)", "csv");
        public final String label, ext;
        Format(String label, String ext) { this.label = label; this.ext = ext; }
        @Override public String toString() { return label; }
    }

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US).withZone(ZoneId.systemDefault());
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US).withZone(ZoneId.systemDefault());
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // ------------------------------------------------------------------ documents

    /** One story: headline, summary, where its coverage comes from, every article with its link, linked stories. */
    public static Doc story(StoryCluster c, Regions regions) {
        Doc d = new Doc(c.headline);
        story(d, c, regions, true);
        d.small().muted("Exported from The Linked Desk on " + WHEN.format(Instant.now()) + ".");
        return d;
    }

    static void story(Doc d, StoryCluster c, Regions regions, boolean top) {
        if (top) d.h1(c.headline); else d.h2(c.headline);
        Regions.Spread sp = regions == null ? null : regions.spread(c);
        d.p().muted(c.category.label() + (c.also != null ? " + " + c.also.label() : "") + " · " + c.sourceCount() + (c.sourceCount() == 1 ? " source" : " sources")
                + " · " + c.articles.size() + (c.articles.size() == 1 ? " article" : " articles") + " · first seen " + WHEN.format(Instant.ofEpochMilli(c.firstSeen))
                + (sp == null || sp.singleSource() ? "" : " · " + sp.label().replaceFirst("^\\d+ outlets? · ", "")));
        if (!c.summary.isEmpty()) d.p().text(c.summary);
        List<StoryCluster> line = c.storyline();
        if (line.size() > 1) {
            d.h3("How this story developed");
            for (StoryCluster s : line) {
                Article lead = EmailDigest.lead(s);
                var b = d.bullet().muted(DAY.format(Instant.ofEpochMilli(s.firstSeen)) + ": ");
                if (s == c) b.bold(s.headline); else b.link(s.headline, lead.url);
            }
        }
        d.h3("Coverage");
        for (Article a : c.articlesNewestFirst()) {
            d.bullet().link(a.title, a.url).br().muted(a.source + " · " + WHEN.format(Instant.ofEpochMilli(a.published)));
        }
        if (!c.links.isEmpty()) {
            d.h3("Linked stories");
            for (StoryLink l : c.links) {
                Article lead = EmailDigest.lead(l.to());
                d.bullet().link(l.to().headline, lead.url).muted(" (" + l.to().category.label() + ", " + l.strength().toLowerCase(Locale.ROOT)
                        + " link · shared: " + String.join(", ", l.sharedTerms()) + ")");
            }
        }
    }

    /** A thread: its topic and every story in it, oldest first, each with its articles. */
    public static Doc thread(StoryThread t, Regions regions) {
        Doc d = new Doc("Thread: " + t.topic());
        d.h1("Thread: " + t.topic());
        List<StoryCluster> s = new ArrayList<>(t.stories());
        s.sort(Comparator.comparingLong(c -> c.firstSeen));
        Set<String> desks = new TreeSet<>();
        for (Category c : t.desks()) desks.add(c.label());
        d.p().muted(s.size() + " stories · " + t.linkCount() + " links · desks: " + String.join(", ", desks)
                + (s.isEmpty() ? "" : " · " + DAY.format(Instant.ofEpochMilli(s.get(0).firstSeen)) + " to " + DAY.format(Instant.ofEpochMilli(s.get(s.size() - 1).lastSeen))));
        for (StoryCluster c : s) {
            Article lead = EmailDigest.lead(c);
            d.bullet().muted(DAY.format(Instant.ofEpochMilli(c.firstSeen)) + " · " + c.category.label() + ": ").link(c.headline, lead.url)
                    .muted(" (" + c.sourceCount() + (c.sourceCount() == 1 ? " source)" : " sources)"));
        }
        for (StoryCluster c : s) story(d, c, regions, false);
        d.small().muted("Exported from The Linked Desk on " + WHEN.format(Instant.now()) + ".");
        return d;
    }

    /** The briefing: Claude's digest (if any), new links between desks, and the top stories per desk. */
    public static Doc briefing(Briefing b, Digest dg, Regions regions) {
        String date = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(b.now()));
        Doc d = new Doc("Briefing · " + date);
        d.h1("Briefing · " + date);
        d.p().muted(String.format("%,d articles from %d outlets in the last 24 hours · %d stories.", b.articles(), b.outlets(), b.stories()));
        if (dg != null && !dg.isEmpty()) {
            d.h2("How today connects");
            d.small().muted("Written by Claude from the day's top stories. Reported facts and Claude's own reading are kept apart.");
            digest(d, "What the reports say", dg.reported(), dg, b, false);
            digest(d, "Claude's reading (inference, not reported fact)", dg.inference(), dg, b, true);
            digest(d, "What to watch", dg.watch(), dg, b, false);
        }
        if (!b.newLinks().isEmpty()) {
            d.h2("Newly connected across desks");
            for (StoryLink l : b.newLinks()) {
                d.bullet().link(l.from().headline, EmailDigest.lead(l.from()).url).muted(" (" + l.from().category.label() + ")").br()
                        .text("↔ ").link(l.to().headline, EmailDigest.lead(l.to()).url).muted(" (" + l.to().category.label() + ") · shared: " + String.join(", ", l.sharedTerms()));
            }
        }
        for (var e : b.desks().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            d.h2(e.getKey().label(), e.getKey().hex());
            for (Briefing.Ranked r : e.getValue()) {
                StoryCluster c = r.story();
                Regions.Spread sp = regions.spread(c);
                d.bullet().boldLink(c.headline, EmailDigest.lead(c).url).br()
                        .muted(coverage(c, sp) + " · " + others(c));
            }
        }
        d.small().muted("Exported from The Linked Desk on " + WHEN.format(Instant.now()) + ".");
        return d;
    }

    private static void digest(Doc d, String title, List<String> lines, Digest dg, Briefing b, boolean italic) {
        if (lines.isEmpty()) return;
        d.h3(title);
        for (String l : lines) {
            Doc.P p = d.bullet();
            java.util.regex.Matcher m = com.linkeddesk.ai.Cite.TAG.matcher(l);
            int last = 0;
            while (m.find()) {
                String t = l.substring(last, m.start());
                if (italic) p.italic(t); else p.text(t);
                String id = "S" + m.group(1);
                StoryCluster c = dg.refs().containsKey(id) ? com.linkeddesk.ai.Cite.find(dg.refs().get(id), b.result().stories()) : null;
                if (c != null) p.link("[" + EmailDigest.lead(c).source + "]", EmailDigest.lead(c).url);
                last = m.end();
            }
            String t = l.substring(last);
            if (italic) p.italic(t); else p.text(t);
        }
    }

    /** "10 outlets · 2 regions" or "Single source". */
    static String coverage(StoryCluster c, Regions.Spread sp) {
        if (sp.singleSource()) return "single source";
        return sp.label();
    }

    private static String others(StoryCluster c) {
        List<String> outlets = new ArrayList<>(new LinkedHashSet<>(c.articlesNewestFirst().stream().map(a -> a.source).toList()));
        return String.join(", ", outlets.subList(0, Math.min(5, outlets.size()))) + (outlets.size() > 5 ? " and " + (outlets.size() - 5) + " more" : "");
    }

    /** A list of stories (what's on screen in Stories, Saved, Search…), each with its articles. */
    public static Doc stories(String title, List<StoryCluster> list, Regions regions) {
        Doc d = new Doc(title);
        d.h1(title);
        d.p().muted(list.size() + (list.size() == 1 ? " story" : " stories") + " · exported " + WHEN.format(Instant.now()));
        for (StoryCluster c : list) story(d, c, regions, false);
        return d;
    }

    // ------------------------------------------------------------------ CSV

    static final String[] HEADER = {"Story", "Desk", "Story first seen", "Published", "Outlet", "Headline", "Link", "Summary"};

    /** One row per article, grouped by story: opens in Excel with accents and links intact. */
    public static String csv(List<StoryCluster> stories) {
        StringBuilder sb = new StringBuilder("﻿");   // tells Excel the file is UTF-8
        row(sb, HEADER);
        for (StoryCluster c : stories)
            for (Article a : c.articlesNewestFirst())
                row(sb, c.headline, c.category.label(), ISO.format(Instant.ofEpochMilli(c.firstSeen)), ISO.format(Instant.ofEpochMilli(a.published)),
                        a.source, a.title, a.url, a.summary);
        return sb.toString();
    }

    static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        sb.append("\r\n");
    }

    /** A CSV cell: quoted when needed, and never taken by Excel as a formula. */
    static String cell(String s) {
        if (s == null) s = "";
        s = s.replace("\r", " ").replace("\n", " ");
        if (!s.isEmpty() && "=+-@\t".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("'") || s.startsWith(" ") || s.endsWith(" ")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /** A whole month of the archive (compressed or not) as a CSV file for Excel. Returns the number of articles. */
    public static int monthCsv(Path monthFile, Path out) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        int n = 0;
        try (BufferedReader r = ArticleStore.reader(monthFile);
             BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write('﻿');
            StringBuilder sb = new StringBuilder();
            row(sb, "Published", "Downloaded", "Feed desk", "Outlet", "Headline", "Link", "Summary", "Id");
            w.write(sb.toString());
            String l;
            while ((l = r.readLine()) != null) {
                Article a = l.isEmpty() ? null : ArticleStore.decode(l);
                if (a == null) continue;
                sb.setLength(0);
                row(sb, ISO.format(Instant.ofEpochMilli(a.published)), ISO.format(Instant.ofEpochMilli(a.fetched)), a.feedCategory.label(), a.source, a.title, a.url, a.summary, a.id);
                w.write(sb.toString());
                n++;
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return n;
    }

    // ------------------------------------------------------------------ writing files

    /** Writes a document in the chosen format (CSV needs the stories: pass them in {@code rows}). */
    public static void write(Doc d, List<StoryCluster> rows, Format f, Path out) throws IOException {
        byte[] data = switch (f) {
            case HTML -> d.toHtml().getBytes(StandardCharsets.UTF_8);
            case MARKDOWN -> d.toMarkdown().getBytes(StandardCharsets.UTF_8);
            case WORD -> d.toDocx();
            case CSV -> csv(rows).getBytes(StandardCharsets.UTF_8);
        };
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A file name from a title: "Belgium strike.html". */
    public static String fileName(String title, Format f) {
        String n = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (n.length() > 80) n = n.substring(0, 80).strip();
        if (n.isEmpty()) n = "export";
        return n + "." + f.ext;
    }
}
