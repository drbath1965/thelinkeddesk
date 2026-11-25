package com.linkeddesk;

/** Runs every test suite; exits with status 1 if anything fails (so the build fails). */
public final class TestMain {
    public static void main(String[] args) throws Exception {
        try { runAll(); }
        catch (Throwable t) {
            // A crash in a test must fail the build, not leave it waiting on a test server thread.
            t.printStackTrace();
            System.out.println("\nTests stopped by an unexpected error.");
            System.exit(1);
        }
    }

    private static void runAll() throws Exception {
        Check c = new Check();
        com.linkeddesk.feeds.FeedParserTest.run(c);
        com.linkeddesk.store.ArticleStoreTest.run(c);
        com.linkeddesk.store.CrashTest.run(c);
        com.linkeddesk.feeds.FetcherTest.run(c);
        com.linkeddesk.app.AppTest.run(c);
        com.linkeddesk.service.ServiceTest.run(c);
        com.linkeddesk.analysis.AnalysisTest.run(c);
        com.linkeddesk.analysis.ReadingTest.run(c);
        com.linkeddesk.schedule.DailySchedulerTest.run(c);
        com.linkeddesk.schedule.WindowsTaskTest.run(c);
        com.linkeddesk.ai.ClaudeClientTest.run(c);
        com.linkeddesk.ai.ClaudeFeaturesTest.run(c);
        com.linkeddesk.alerts.AlertsTest.run(c);
        com.linkeddesk.store.StorageTest.run(c);
        com.linkeddesk.export.ExportTest.run(c);
        com.linkeddesk.feeds.MoreSourcesTest.run(c);
        com.linkeddesk.service.P8Test.run(c);
        com.linkeddesk.ai.P9Test.run(c);
        com.linkeddesk.ai.P10Test.run(c);
        com.linkeddesk.ai.P11Test.run(c);
        System.out.println();
        System.out.println(c.passed() + " checks passed, " + c.failures().size() + " failed");
        c.failures().forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(c.failures().isEmpty() ? 0 : 1);
    }
}
