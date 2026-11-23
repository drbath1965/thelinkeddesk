package com.linkeddesk.feeds;

import com.linkeddesk.model.Article;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Downloads feeds in parallel, politely:
 *  - conditional requests (If-None-Match / If-Modified-Since): an unchanged feed answers 304 and costs nothing;
 *  - gzip/deflate compression;
 *  - up to {@link #ATTEMPTS} tries on time-outs, connection errors, 429 and 5xx, waiting longer each time;
 *  - at most {@link #PER_HOST} requests at once to the same site;
 *  - can be cancelled at any moment; each feed's result is handed over as soon as it is done.
 */
public final class FeedFetcher {
    public static final int ATTEMPTS = 3;
    public static final int PER_HOST = 2;
    static long[] backoffMs = {2_000, 6_000};      // waits before the 2nd and 3rd tries (tests shorten these)
    static Duration requestTimeout = Duration.ofSeconds(30);

    /** The outcome of one feed. */
    public record FeedItems(FeedSource feed, List<Article> items, String error, boolean notModified,
                            String etag, String lastModified, int attempts) {
        public boolean ok() { return error == null; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final Map<String, Semaphore> hosts = new ConcurrentHashMap<>();

    /**
     * Downloads every feed. {@code onEach} receives each result as soon as it is ready (from worker threads),
     * so finished feeds are kept even if the run is cancelled.
     */
    public void fetchAll(List<FeedSource> feeds, FeedHealth health, Consumer<FeedItems> onEach, BooleanSupplier cancelled) {
        long now = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (FeedSource f : feeds) futures.add(pool.submit(() -> onEach.accept(fetchOne(f, health, now, cancelled))));
            for (Future<?> fu : futures) {
                while (true) {
                    try { fu.get(200, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException t) { if (cancelled.getAsBoolean()) { pool.shutdownNow(); return; } }
                    catch (Exception e) { break; }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    FeedItems fetchOne(FeedSource f, FeedHealth health, long now, BooleanSupplier cancelled) {
        if (f.url().startsWith("file:")) {
            try {
                byte[] body = Files.readAllBytes(Path.of(URI.create(f.url())));
                return new FeedItems(f, FeedParser.parse(body, f, now), null, false, null, null, 1);
            } catch (Exception e) {
                return new FeedItems(f, List.of(), describe(e), false, null, null, 1);
            }
        }
        String host;
        try { host = URI.create(f.url()).getHost(); } catch (RuntimeException e) { return new FeedItems(f, List.of(), "Not a valid web address", false, null, null, 0); }
        Semaphore gate = hosts.computeIfAbsent(host == null ? "" : host.toLowerCase(Locale.ROOT), h -> new Semaphore(PER_HOST));
        FeedHealth.Entry known = health == null ? null : health.get(f.url());
        String lastError = null;
        int attempt = 0;
        while (attempt < ATTEMPTS) {
            if (cancelled.getAsBoolean()) return new FeedItems(f, List.of(), "Cancelled", false, null, null, attempt);
            if (attempt > 0 && !sleep(backoffMs[Math.min(attempt - 1, backoffMs.length - 1)], cancelled))
                return new FeedItems(f, List.of(), "Cancelled", false, null, null, attempt);
            attempt++;
            boolean acquired = false;
            try {
                while (!(acquired = gate.tryAcquire(200, TimeUnit.MILLISECONDS))) {
                    if (cancelled.getAsBoolean()) return new FeedItems(f, List.of(), "Cancelled", false, null, null, attempt);
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(f.url()))
                        .timeout(requestTimeout)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TheLinkedDesk/1.1 RSS reader")
                        .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
                        .header("Accept-Encoding", "gzip, deflate")
                        .GET();
                if (known != null && !known.etag.isEmpty()) rb.header("If-None-Match", known.etag);
                if (known != null && !known.lastModified.isEmpty()) rb.header("If-Modified-Since", known.lastModified);
                CompletableFuture<HttpResponse<byte[]>> call = http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> resp;
                while (true) {
                    try { resp = call.get(200, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException t) {
                        if (cancelled.getAsBoolean()) { call.cancel(true); return new FeedItems(f, List.of(), "Cancelled", false, null, null, attempt); }
                    }
                }
                int code = resp.statusCode();
                String etag = resp.headers().firstValue("ETag").orElse(null);
                String lm = resp.headers().firstValue("Last-Modified").orElse(null);
                if (code == 304) return new FeedItems(f, List.of(), null, true, etag, lm, attempt);
                if (code == 200) {
                    byte[] body = decode(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(""));
                    return new FeedItems(f, FeedParser.parse(body, f, now), null, false, etag, lm, attempt);
                }
                lastError = "HTTP " + code + reason(code);
                if (!(code == 429 || code >= 500)) break;          // 403, 404 etc. won't fix themselves
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                lastError = describe(c);
                if (!(c instanceof IOException)) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FeedItems(f, List.of(), "Cancelled", false, null, null, attempt);
            } catch (Exception e) {
                lastError = describe(e);                               // e.g. the feed isn't valid XML
                break;
            } finally {
                if (acquired) gate.release();
            }
        }
        return new FeedItems(f, List.of(), lastError, false, null, null, attempt);
    }

    static byte[] decode(byte[] body, String encoding) throws IOException {
        String enc = encoding.toLowerCase(Locale.ROOT);
        boolean gzipMagic = body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
        if (enc.contains("gzip") || gzipMagic) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) { return in.readAllBytes(); }
        }
        if (enc.contains("deflate")) {
            try (var in = new InflaterInputStream(new ByteArrayInputStream(body))) { return in.readAllBytes(); }
        }
        return body;
    }

    private static boolean sleep(long ms, BooleanSupplier cancelled) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled.getAsBoolean()) return false;
            try { Thread.sleep(Math.min(100, end - System.currentTimeMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    private static String reason(int code) {
        return switch (code) {
            case 401, 403 -> " (the site refused automated readers)";
            case 404, 410 -> " (feed address no longer exists)";
            case 429 -> " (too many requests)";
            case 500, 502, 503, 504 -> " (site error)";
            default -> "";
        };
    }

    static String describe(Throwable e) {
        if (e instanceof HttpTimeoutException) return "Timed out";
        if (e instanceof java.net.ConnectException) return "Could not connect";
        if (e instanceof java.nio.channels.UnresolvedAddressException || e instanceof java.net.UnknownHostException) return "Site not found";
        if (e instanceof org.xml.sax.SAXException) return "Not a valid feed (" + e.getMessage() + ")";
        String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }
}
