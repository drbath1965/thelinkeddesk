package com.linkeddesk.app;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.StoryThread;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point.
 *   java -jar LinkedDesk.jar            opens the window (a second launch brings the open window to the front)
 *   java -jar LinkedDesk.jar --fetch    downloads once, saves, logs and exits (used by the daily Windows task)
 *   java -jar LinkedDesk.jar --summary  prints the current stories, links and threads (no window)
 */
public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Logs.error("Unexpected error in " + t.getName(), e));
        Settings settings = Settings.load();
        NewsService service = new NewsService(new ArticleStore(), settings);

        if (a.contains("--fetch")) {
            try {
                service.loadAll();
                FetchReport r = service.fetchNow(null, () -> false);
                System.out.println(r.detail());
                // The daily task also has Claude write the digest and check the day's links (if switched on),
                // so they are ready when you open the app.
                String claude = service.dailyClaude();
                Logs.run("claude", claude, "", 0);
                System.out.println(claude);
                // Watchlist notification (the app may be closed; the icon appears just long enough to show it).
                try {
                    var shown = service.notifyAfterDownload(r, com.linkeddesk.alerts.Tray.brief());
                    if (shown != null) System.out.println("Notified: " + shown.title());
                } catch (Exception e) { Logs.error("Watchlist notification after the daily download", e); }
                // Email the briefing (once a day, if switched on).
                try { System.out.println(service.emailBriefing(false)); }
                catch (Exception e) { Logs.error("Emailing the briefing", e); System.out.println("Email failed: " + e.getMessage()); }
                // Archive upkeep: compress old months, update the search index, weekly backup if due.
                String m = service.maintenance();
                if (!m.isEmpty()) System.out.println(m);
            } catch (NewsService.BusyException busy) {
                Logs.run("download", "skipped: another download was already running", "", 0);
                System.out.println("Skipped: " + busy.getMessage());
            } catch (Exception e) {
                Logs.error("Daily download failed", e);
                System.exit(1);
            }
            return;
        }
        if (a.contains("--report")) {
            // --report [YYYY-MM-DD] [file.docx|.html|.md]: the weekly report for the week holding that day (default: last week).
            int i = a.indexOf("--report");
            java.time.LocalDate day = java.time.LocalDate.now().minusDays(7);
            String file = null;
            for (int k = i + 1; k < a.size(); k++) {
                String x = a.get(k);
                if (x.matches("\\d{4}-\\d{2}-\\d{2}")) day = java.time.LocalDate.parse(x);
                else if (!x.startsWith("--")) file = x;
            }
            service.loadAll();
            var doc = service.weeklyReport(day);
            java.nio.file.Path out = java.nio.file.Path.of(file != null ? file
                    : "Linked Desk weekly report " + com.linkeddesk.export.WeeklyReport.weekStart(day) + ".docx");
            String n = out.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            var fmt = n.endsWith(".html") || n.endsWith(".htm") ? com.linkeddesk.export.Exporter.Format.HTML
                    : n.endsWith(".md") ? com.linkeddesk.export.Exporter.Format.MARKDOWN : com.linkeddesk.export.Exporter.Format.WORD;
            com.linkeddesk.export.Exporter.write(doc, List.of(), fmt, out.toAbsolutePath());
            System.out.println("Weekly report written: " + out.toAbsolutePath());
            return;
        }
        if (a.contains("--backup")) {
            System.out.println(service.backupNow().describe());
            return;
        }
        if (a.contains("--print-task")) {
            var act = com.linkeddesk.schedule.WindowsTask.currentAction();
            System.out.println("Program:   " + act.program() + "\nArguments: " + act.arguments() + "\nFolder:    " + act.folder());
            return;
        }
        if (a.contains("--summary")) {
            service.loadAll();
            int days = a.contains("--all") ? 0 : settings.windowDays();
            printSummary(service.analyze(days));
            return;
        }

        if (a.contains("--after-update")) SingleInstance.waitForOldWindow(AppPaths.home(), 30_000);
        MainWindow[] window = new MainWindow[1];
        boolean first = SingleInstance.claim(AppPaths.home(), () -> SwingUtilities.invokeLater(() -> {
            if (window[0] != null) window[0].bringToFront();
        }));
        if (!first) {
            System.out.println("The Linked Desk is already open; brought it to the front.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            com.linkeddesk.ui.Theme.apply(service.settings());
            window[0] = new MainWindow(service);
            window[0].setVisible(true);
            window[0].start();
        });
    }

    private static void printSummary(Analyzer.Result r) {
        System.out.printf("%d articles -> %d stories, %d threads%n%n", r.articleCount(), r.stories().size(), r.threads().size());
        for (StoryCluster c : r.stories()) {
            if (c.sourceCount() < 2 && c.links.isEmpty()) continue;
            System.out.printf("[%s] %s  (%d sources, %d articles)%n", c.category.label(), c.headline, c.sourceCount(), c.articles.size());
            for (StoryLink l : c.links)
                System.out.printf("     ↔ %-8s %.2f [%s] %s  {%s}%n", l.strength(), l.score(), l.to().category.label(),
                        l.to().headline, String.join(", ", l.sharedTerms()));
        }
        System.out.println();
        for (StoryThread t : r.threads())
            System.out.printf("THREAD %s: %d stories, desks %s%n", t.topic(), t.stories().size(), t.desks());
    }
}
