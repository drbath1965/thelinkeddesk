package com.linkeddesk.export;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.app.Settings;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryThread;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.StorageTest;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipInputStream;

/** P6 export: a story, thread, briefing or list as HTML, Markdown, Word and CSV (EXP-F01) and the weekly report (EXP-F02). */
public final class ExportTest {
    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();
        c.suite("Export stories, threads and the briefing (EXP-F01)");
        String oldHome = System.getProperty("linkeddesk.home");
        Path home = Files.createTempDirectory("ld-export");
        System.setProperty("linkeddesk.home", home.toString());
        Files.writeString(home.resolve(".feeds-v1.1"), "test");
        try {
            Settings st = Settings.load();
            ArticleStore store = new ArticleStore(home.resolve("data"));
            store.load();
            List<Article> real = StorageTest.real(now - 3_600_000L);
            store.addAll(real);
            NewsService svc = new NewsService(store, st);
            Analyzer.Result r = svc.analyze(30);
            StoryCluster big = r.stories().stream().filter(s -> s.articles.size() >= 5 && !s.links.isEmpty()).findFirst().orElse(r.stories().get(0));
            Regions reg = svc.regions();
            Doc d = Exporter.story(big, reg);
            String html = d.toHtml(), md = d.toMarkdown(), text = d.toText();
            int links = 0;
            for (Article a : big.articles) if (html.contains("href=\"" + Doc.esc(a.url) + "\"")) links++;
            c.that(links == big.articles.size(), "HTML: every article links to its page (" + links + ")");
            c.that(html.startsWith("<!DOCTYPE html>") && html.contains("<meta charset=\"utf-8\">") && html.contains("<h1>"), "a complete web page (opens in any browser)");
            c.that(md.startsWith("# ") && md.contains("](" + big.articles.get(0).url.replace(")", "%29").replace(" ", "%20") + ")") && md.contains("### Coverage"), "Markdown with links and headings");
            c.that(text.contains("<" + big.articles.get(0).url + ">") && !text.contains("<a "), "plain text keeps the addresses");
            c.that(html.contains("Linked stories") && md.contains("Linked stories"), "linked stories are included");

            // Word
            byte[] docx = d.toDocx();
            Map<String, String> parts = unzip(docx);
            c.that(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml", "word/_rels/document.xml.rels")),
                    "Word: a .docx package with document, styles, numbering and links");
            boolean wellFormed = true;
            for (var e : parts.entrySet()) try { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8))); }
            catch (Exception ex) { wellFormed = false; }
            c.that(wellFormed, "every part is well-formed XML");
            int rels = parts.get("word/_rels/document.xml.rels").split("TargetMode=\"External\"").length - 1;
            int hl = parts.get("word/document.xml").split("<w:hyperlink ").length - 1;
            c.that(rels == hl && hl >= big.articles.size(), "headlines are clickable links in Word (" + hl + ")");
            Doc tricky = new Doc("Ampersand & <tags> \"quotes\"");
            tricky.h1("A & B <c> \u0001bell");
            tricky.p().link("AT&T's \"deal\"", "https://example.org/?a=1&b=2");
            String tx = unzip(tricky.toDocx()).get("word/document.xml");
            c.that(tx.contains("A &amp; B &lt;c&gt; bell") && unzip(tricky.toDocx()).get("word/_rels/document.xml.rels").contains("?a=1&amp;b=2"), "special characters are escaped (and control characters dropped)");

            // CSV
            String csv = Exporter.csv(List.of(big));
            String[] lines = csv.split("\r\n");
            c.that(csv.startsWith("﻿Story,Desk,") && lines.length == 1 + big.articles.size(), "CSV: a header and one row per article, marked UTF-8 for Excel");
            c.eq("\"'=HYPERLINK(\"\"x\"\")\"", Exporter.cell("=HYPERLINK(\"x\")"), "cells Excel would run as formulas are made plain text");
            c.eq("\"a, b\"", Exporter.cell("a, b"), "commas are quoted");
            c.eq("line one line two", Exporter.cell("line one\nline two"), "line breaks inside a cell are removed");

            // Thread, briefing, list
            StoryThread th = r.threads().isEmpty() ? null : r.threads().get(0);
            if (th != null) {
                String tm = Exporter.thread(th, reg).toMarkdown();
                c.that(tm.startsWith("# Thread: ") && th.stories().stream().allMatch(s -> tm.contains(Doc.md(s.headline))), "a thread lists every story in it (" + th.stories().size() + ")");
            }
            var b = svc.briefing();
            String bh = Exporter.briefing(b, null, reg).toHtml();
            c.that(bh.contains("Briefing · ") && b.desks().values().stream().flatMap(List::stream).allMatch(x -> bh.contains(Doc.esc(x.story().headline))), "the briefing: every top story per desk");
            c.that(b.newLinks().isEmpty() || bh.contains("Newly connected across desks"), "with the day's new links between desks");
            String lst = Exporter.stories("Search results", r.stories().subList(0, 5), reg).toMarkdown();
            c.that(lst.startsWith("# Search results") && lst.split("\n## ").length == 6, "a list of stories (e.g. search results)");
            c.eq("A B story part 2.docx", Exporter.fileName("A/B story: part 2?", Exporter.Format.WORD), "file names lose characters Windows doesn't allow");

            // Files
            Path dir = Files.createTempDirectory("ld-exp-out");
            for (Exporter.Format f : Exporter.Format.values()) Exporter.write(d, List.of(big), f, dir.resolve("story." + f.ext));
            c.that(Files.size(dir.resolve("story.docx")) > 1000 && Files.readString(dir.resolve("story.csv")).startsWith("﻿"), "saving writes each format");

            // Clipboard contents
            var sel = new com.linkeddesk.ui.TestAccess.Clip(d);
            c.that(sel.html().contains("<a href=") && sel.text().contains(big.articles.get(0).url), "Copy puts formatted text (links kept) and plain text on the clipboard");

            // A month for Excel
            Path month = store.files().get(store.files().size() - 1);
            int n = Exporter.monthCsv(month, dir.resolve("month.csv"));
            List<String> ml = Files.readAllLines(dir.resolve("month.csv"));
            c.that(n > 0 && ml.size() == n + 1 && ml.get(0).startsWith("﻿Published,Downloaded"), String.format("a whole month exports as CSV for Excel (%,d articles)", n));

            // ------------------------------------------------------------------ EXP-F02
            c.suite("Weekly report (EXP-F02)");
            c.eq("November 16–22, 2026", WeeklyReport.label(LocalDate.of(2026, 11, 16)), "a week's label");
            c.eq("November 30 – December 6, 2026", WeeklyReport.label(LocalDate.of(2026, 11, 30)), "… across months");
            c.eq("December 28, 2026 – January 3, 2027", WeeklyReport.label(LocalDate.of(2026, 12, 28)), "… across years");
            c.eq(LocalDate.of(2026, 11, 16), WeeklyReport.weekStart(LocalDate.of(2026, 11, 22)), "weeks run Monday to Sunday");
            LocalDate newestDay = Instant.ofEpochMilli(now - 3_600_000L).atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate full = newestDay.minusDays(1);
            svc.watches().save(List.of(new com.linkeddesk.alerts.Watches.Watch("Ukraine", "Ukraine OR Kyiv", true)));
            svc.tickers().save(List.of(new com.linkeddesk.alerts.Tickers.Ticker("NVDA", com.linkeddesk.alerts.Tickers.knownNames("NVDA"))));
            Doc rep = svc.weeklyReport(full);
            String rm = rep.toMarkdown();
            c.that(rm.startsWith("# Week of ") && rm.contains("## Top stories") && rm.contains("articles from"), "the report: overview and top stories");
            int desks = 0;
            for (Category cat : Category.values()) if (rm.contains("### " + cat.label().replace("&", "&"))) desks++;
            c.that(desks >= 3, "top stories per desk (" + desks + " desks)");
            c.that(rm.contains("## Connections across desks") && rm.contains("## Threads"), "connections across desks and threads");
            c.that(rm.contains("## Your watchlist") && rm.contains("**Ukraine**") && rm.contains("## Markets"), "your watchlist and tickers");
            Map<String, String> rp = unzip(rep.toDocx());
            c.that(rp.get("word/document.xml").contains("Top stories") && rp.get("word/document.xml").split("<w:hyperlink ").length > 20, "as a Word file with working links");
            Doc empty = svc.weeklyReport(LocalDate.of(2019, 3, 6));
            c.that(empty.toText().contains("Nothing was saved for this week"), "a week outside the archive says so");
            // From the command line
            Path out = dir.resolve("report.docx");
            com.linkeddesk.app.App.main(new String[]{"--report", full.toString(), out.toString()});
            c.that(Files.exists(out) && unzip(Files.readAllBytes(out)).get("word/document.xml").contains("Top stories"), "LinkedDesk.jar --report writes it too (for a scheduled task)");
        } finally {
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }

    static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) m.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return m;
    }
}
