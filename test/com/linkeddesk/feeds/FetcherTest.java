package com.linkeddesk.feeds;

import com.linkeddesk.Check;
import com.linkeddesk.model.Category;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/** FET-F03 / FET-F05 / UI-F03: feed health, polite downloading and cancelling, against a local web server. */
public final class FetcherTest {
    static final String RSS = "<rss><channel><item><title>Story one</title><link>https://x/1</link></item>"
            + "<item><title>Story two</title><link>https://x/2</link></item></channel></rss>";

    private static void reply(HttpExchange ex, int code, byte[] body, String... headers) throws java.io.IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        ex.sendResponseHeaders(code, code == 304 ? -1 : body.length);
        if (code != 304) ex.getResponseBody().write(body);
        ex.close();
    }

    public static void run(Check c) throws Exception {
        c.suite("Feed health (FET-F03)");
        FeedHealth h = new FeedHealth(Files.createTempFile("health", ".tsv"));
        FeedSource f = new FeedSource(Category.WORLD, "Test feed", "https://example.org/rss");
        long t = 1_795_000_000_000L;
        for (int i = 1; i <= 6; i++) h.recordFailure(f, "HTTP 403", t + i);
        c.that(h.isDue(f, t + 10), "still tried after 6 failures in a row");
        h.recordFailure(f, "HTTP 403", t + 7);
        c.that(!h.isDue(f, t + 10), "paused after the 7th failure in a row");
        c.eq(FeedHealth.State.PAUSED, h.find(f.url()).state(t + 10), "shown as Paused");
        c.that(h.isDue(f, t + 7 + FeedHealth.PAUSE_MS), "tried again a week later");
        h.recordFailure(f, "HTTP 403", t + 8 + FeedHealth.PAUSE_MS);
        c.that(!h.isDue(f, t + 9 + FeedHealth.PAUSE_MS), "a failed weekly retry pauses it for another week");
        h.resume(f.url());
        c.that(h.isDue(f, t + 10 + FeedHealth.PAUSE_MS), "Retry now un-pauses it");
        h.recordSuccess(f, 12, false, "\"etag-1\"", "Mon, 23 Nov 2026 08:00:00 GMT", t + 11 + FeedHealth.PAUSE_MS);
        c.eq(0, h.find(f.url()).failures, "one success clears the failures");
        h.save();
        FeedHealth back = new FeedHealth(h.file()).load();
        c.eq("\"etag-1\"", back.find(f.url()).etag, "health saved and read back (ETag)");
        c.eq("HTTP 403", back.find(f.url()).lastError, "last error kept for the health table");

        c.suite("Polite downloading (FET-F05) and cancel (UI-F03)");
        FeedFetcher.backoffMs = new long[]{50, 100};
        AtomicInteger flaky = new AtomicInteger(), notFound = new AtomicInteger(), inFlight = new AtomicInteger();
        List<Integer> peak = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/etag", ex -> {
            if ("\"v1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) reply(ex, 304, new byte[0]);
            else reply(ex, 200, RSS.getBytes(StandardCharsets.UTF_8), "ETag", "\"v1\"");
        });
        server.createContext("/gzip", ex -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(RSS.getBytes(StandardCharsets.UTF_8)); }
            boolean asked = String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")).contains("gzip");
            reply(ex, 200, asked ? bo.toByteArray() : RSS.getBytes(StandardCharsets.UTF_8), asked ? new String[]{"Content-Encoding", "gzip"} : new String[0]);
        });
        server.createContext("/flaky", ex -> {
            if (flaky.incrementAndGet() == 1) reply(ex, 503, "busy".getBytes());
            else reply(ex, 200, RSS.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/gone", ex -> { notFound.incrementAndGet(); reply(ex, 404, "no".getBytes()); });
        server.createContext("/slow", ex -> {
            int n = inFlight.incrementAndGet();
            peak.add(n);
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            inFlight.decrementAndGet();
            reply(ex, 200, RSS.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/hang", ex -> {
            try { Thread.sleep(30_000); } catch (InterruptedException ignored) { }
            reply(ex, 200, RSS.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            FeedFetcher ff = new FeedFetcher();
            FeedHealth health = new FeedHealth(Files.createTempFile("health2", ".tsv"));
            FeedSource etag = new FeedSource(Category.WORLD, "ETag feed", base + "/etag");
            FeedFetcher.FeedItems first = ff.fetchOne(etag, health, System.currentTimeMillis(), () -> false);
            c.eq(2, first.items().size(), "first download reads the feed");
            health.recordSuccess(etag, 2, false, first.etag(), first.lastModified(), System.currentTimeMillis());
            FeedFetcher.FeedItems second = ff.fetchOne(etag, health, System.currentTimeMillis(), () -> false);
            c.that(second.ok() && second.notModified() && second.items().isEmpty(), "unchanged feed answers 304 and costs nothing");

            FeedFetcher.FeedItems gz = ff.fetchOne(new FeedSource(Category.WORLD, "Gzip", base + "/gzip"), null, System.currentTimeMillis(), () -> false);
            c.eq(2, gz.items().size(), "asks for gzip and unpacks it");

            FeedFetcher.FeedItems retry = ff.fetchOne(new FeedSource(Category.WORLD, "Flaky", base + "/flaky"), null, System.currentTimeMillis(), () -> false);
            c.that(retry.ok() && retry.attempts() == 2 && retry.items().size() == 2, "a 503 is retried and the second try succeeds");

            FeedFetcher.FeedItems gone = ff.fetchOne(new FeedSource(Category.WORLD, "Gone", base + "/gone"), null, System.currentTimeMillis(), () -> false);
            c.that(!gone.ok() && notFound.get() == 1, "a 404 is not retried (it won't fix itself)");
            c.eq("HTTP 404 (feed address no longer exists)", gone.error(), "and the error says why");

            FeedFetcher.FeedItems dead = ff.fetchOne(new FeedSource(Category.WORLD, "Dead", "http://127.0.0.1:9/rss"), null, System.currentTimeMillis(), () -> false);
            c.that(!dead.ok() && dead.attempts() == FeedFetcher.ATTEMPTS, "connection errors are tried " + FeedFetcher.ATTEMPTS + " times");

            List<FeedSource> six = new ArrayList<>();
            for (int i = 0; i < 6; i++) six.add(new FeedSource(Category.WORLD, "Slow " + i, base + "/slow?n=" + i));
            List<FeedFetcher.FeedItems> got = Collections.synchronizedList(new ArrayList<>());
            ff.fetchAll(six, null, got::add, () -> false);
            int max = peak.stream().mapToInt(Integer::intValue).max().orElse(0);
            c.that(got.size() == 6 && max <= FeedFetcher.PER_HOST, "never more than " + FeedFetcher.PER_HOST + " requests at once to one site (peak " + max + ")");

            // Cancel: three feeds that would hang 30 s; cancel after 1 s.
            List<FeedSource> hang = new ArrayList<>();
            for (int i = 0; i < 3; i++) hang.add(new FeedSource(Category.WORLD, "Hang " + i, base.replace("127.0.0.1", "localhost") + "/hang?n=" + i));
            hang.add(new FeedSource(Category.WORLD, "Quick", base + "/etag"));
            long start = System.currentTimeMillis();
            List<FeedFetcher.FeedItems> partial = Collections.synchronizedList(new ArrayList<>());
            new Thread(() -> { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } cancelFlag = true; }).start();
            ff.fetchAll(hang, null, partial::add, () -> cancelFlag);
            long took = System.currentTimeMillis() - start;
            c.that(took < 5000, "Cancel stops the download within 5 s (took " + String.format("%.1f", took / 1000.0) + " s)");
            c.that(partial.stream().anyMatch(x -> x.feed().name().equals("Quick") && x.ok()), "feeds finished before Cancel are kept");
        } finally {
            server.stop(0);
            FeedFetcher.backoffMs = new long[]{2_000, 6_000};
        }
    }

    static volatile boolean cancelFlag;
}
