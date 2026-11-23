package com.linkeddesk.store;

import com.linkeddesk.Check;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** QA-F01 / STO-F02 / QA-F03: archive format, de-duplication, crash safety, lazy months, backups. */
public final class ArticleStoreTest {
    static Article art(String id, String title, String source, long t) {
        return new Article(id, "https://x/" + id, title, "Summary of " + title, source, Category.WORLD, t, t);
    }

    public static void run(Check c) throws Exception {
        c.suite("Article archive (QA-F01, STO-F02)");
        Path dir = Files.createTempDirectory("ld-store");
        long now = System.currentTimeMillis();

        Article tricky = new Article("id1", "https://x/1", "Tab\there and \\ slash", "Line one\nline two", "BBC", Category.FINANCE, now, now);
        Article back = ArticleStore.decode(ArticleStore.encode(tricky));
        c.eq(tricky.title, back.title, "tabs and backslashes survive save and load");
        c.eq(tricky.summary, back.summary, "line breaks survive save and load");
        c.eq(Category.FINANCE, back.feedCategory, "desk survives save and load");
        c.that(ArticleStore.decode("garbage line") == null && ArticleStore.decode("a\tb\tc\td\te\tWORLD\tnot-a-number\t1") == null, "unreadable lines are rejected, not guessed");

        ArticleStore s = new ArticleStore(dir);
        s.load();
        c.eq(2, s.addAll(List.of(art("a", "Iran tankers", "BBC", now), art("b", "Fed holds", "CNBC", now))).size(), "two new articles saved");
        c.eq(0, s.addAll(List.of(art("a", "Iran tankers", "BBC", now))).size(), "same URL is not saved twice");
        c.eq(0, s.addAll(List.of(art("a2", "Iran Tankers!", "BBC", now))).size(), "same outlet + same headline (new URL) is not saved twice");
        c.eq(1, s.addAll(List.of(art("a3", "Iran tankers", "Al Jazeera", now))).size(), "same headline from another outlet is kept");
        c.eq("a", s.canonicalId(art("a2", "Iran Tankers!", "BBC", now)), "duplicate headline maps to the stored article");

        // Another process (the daily task) appends; this store picks up only the new lines.
        ArticleStore other = new ArticleStore(dir);
        other.load();
        other.addAll(List.of(art("c", "Ukraine talks", "DW", now)));
        c.that(s.reloadIfChanged(), "notices data written by another process");
        c.eq(4, s.size(), "and reads the new article");
        c.that(!s.reloadIfChanged(), "no work when nothing changed");

        // ---- crash safety: a line cut off half-way
        Path month = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("articles-")).findFirst().orElseThrow();
        Files.writeString(month, "zz\thttps://x/zz\tHalf-writ", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ArticleStore afterCrash = new ArticleStore(dir);
        afterCrash.load();
        c.eq(4, afterCrash.size(), "after a crash, every complete article still loads");
        afterCrash.addAll(List.of(art("d", "Estonia border", "BBC", now)));
        c.eq(1, afterCrash.damagedLines(), "the cut-off line is counted as damaged");
        ArticleStore reread = new ArticleStore(dir);
        reread.load();
        c.eq(5, reread.size(), "the next save starts on a fresh line, so nothing after the crash is lost");
        c.eq("Estonia border", reread.get("d") == null ? null : reread.get("d").title, "article saved after the crash reads back intact");

        // ---- backups
        Path bak = dir.resolve("backup").resolve(month.getFileName() + ".bak");
        c.that(Files.exists(bak), "a backup copy of the current month is kept in data/backup");

        // ---- lazy months: an old month is indexed, not loaded, until needed
        YearMonth old = YearMonth.now(ZoneId.systemDefault()).minusMonths(6);
        long oldT = old.atDay(10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.writeString(dir.resolve("articles-" + old + ".tsv"),
                ArticleStore.encode(art("old1", "Old summit story", "BBC", oldT)) + "\n" + ArticleStore.encode(art("old2", "Old oil story", "CNBC", oldT)) + "\n");
        ArticleStore lazy = new ArticleStore(dir);
        lazy.load();
        c.eq(7, lazy.size(), "total count includes old months");
        c.eq(5, lazy.loadedCount(), "only recent months are held in memory at start-up");
        c.eq(0, lazy.addAll(List.of(art("old1", "Old summit story", "BBC", oldT))).size(), "duplicates are caught even in months not loaded");
        List<Article> found = new ArrayList<>();
        lazy.scan(a -> a.title.contains("oil"), found::add);
        c.eq(1, found.size(), "search reads old months straight from disk");
        c.eq(5, lazy.loadedCount(), "searching doesn't keep old months in memory");
        c.eq(7, lazy.since(oldT - 1000).size(), "asking for older dates loads those months");
    }
}
