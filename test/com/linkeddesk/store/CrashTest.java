package com.linkeddesk.store;

import com.linkeddesk.Check;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** STO-F02: kill the app (like a power cut) in the middle of a download; earlier data must survive. */
public final class CrashTest {
    public static void run(Check c) throws Exception {
        c.suite("Killed mid-download (STO-F02)");
        Path home = Files.createTempDirectory("ld-crash");
        Path res = Path.of("test/resources").toAbsolutePath();
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.createContext("/slow", ex -> {
            try { Thread.sleep(30_000); } catch (InterruptedException ignored) { }
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        slow.start();
        try {
            Files.writeString(home.resolve(".feeds-v1.1"), "test: no v1.1 feed upgrade"); Files.writeString(home.resolve(".feeds-v1.8"), "test: no v1.1 feed upgrade");   // keep the test offline
            // Day 1: a normal download.
            Files.writeString(home.resolve("feeds.txt"), "World | BBC World | " + res.resolve("bbc_world.xml").toUri() + "\n");
            Process day1 = launch(home);
            day1.waitFor();
            ArticleStore s1 = new ArticleStore(home.resolve("data"));
            s1.load();
            int before = s1.size();

            // Day 2: one quick feed and one that hangs; the process is killed (-9) while it waits.
            Files.writeString(home.resolve("feeds.txt"), "World | Al Jazeera | " + res.resolve("aljazeera.xml").toUri() + "\n"
                    + "World | Hangs | http://127.0.0.1:" + slow.getAddress().getPort() + "/slow\n");
            Process day2 = launch(home);
            Thread.sleep(2500);
            day2.destroyForcibly();
            day2.waitFor();

            ArticleStore s2 = new ArticleStore(home.resolve("data"));
            s2.load();
            c.eq(6, before, "day 1 saved its 6 articles");
            c.eq(11, s2.size(), "after the kill: day 1's 6 articles plus the 5 finished before the kill are all there");
            c.eq(0, s2.damagedLines(), "no damaged lines");
            String raw = Files.readString(Files.list(home.resolve("data")).filter(p -> p.toString().endsWith(".tsv")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
            c.that(raw.endsWith("\n"), "archive file ends cleanly");
        } finally {
            slow.stop(0);
        }
    }

    private static Process launch(Path home) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Dlinkeddesk.home=" + home,
                "-cp", System.getProperty("java.class.path"), "com.linkeddesk.app.App", "--fetch");
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }
}
