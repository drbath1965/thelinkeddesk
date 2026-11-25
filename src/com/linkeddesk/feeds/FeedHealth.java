package com.linkeddesk.feeds;

import com.linkeddesk.app.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Health of every feed, kept in feed-health.tsv: last success, last error, failures in a row,
 * articles added per day, pause state, and the ETag/Last-Modified values used for polite re-downloads.
 *
 * A feed that fails {@link #PAUSE_AFTER} times in a row is paused for a week, then tried again.
 * One success clears the failures.
 */
public final class FeedHealth {
    public static final int PAUSE_AFTER = 7;
    public static final long PAUSE_MS = 7L * 24 * 3600_000;
    private static final int KEEP_DAYS = 30;

    public enum State { NEW, OK, FAILING, PAUSED, NOT_MODIFIED }

    /** Everything known about one feed (keyed by URL). */
    public static final class Entry {
        public final String url;
        public String name = "";
        public long lastSuccess, lastErrorAt, pausedUntil, lastChecked;
        public String lastError = "";
        public int failures;
        public String etag = "", lastModified = "";
        public final TreeMap<LocalDate, Integer> addedPerDay = new TreeMap<>();
        Entry(String url) { this.url = url; }

        public State state(long now) {
            if (pausedUntil > now) return State.PAUSED;
            if (failures > 0) return State.FAILING;
            return lastSuccess == 0 ? State.NEW : State.OK;
        }

        /** Average new articles per day over the last 7 days that had a download. */
        public double perDay(LocalDate today) {
            int sum = 0, days = 0;
            for (var e : addedPerDay.tailMap(today.minusDays(6), true).entrySet()) { sum += e.getValue(); days++; }
            return days == 0 ? 0 : (double) sum / days;
        }
    }

    private final Path file;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FeedHealth() { this(AppPaths.home().resolve("feed-health.tsv")); }
    public FeedHealth(Path file) { this.file = file; }

    public Path file() { return file; }

    private boolean unreadable;

    public synchronized FeedHealth load() {
        List<String> lines;
        try { lines = com.linkeddesk.app.SafeFiles.readLines(file); unreadable = false; }
        catch (com.linkeddesk.app.SafeFiles.Unreadable u) { unreadable = true; return this; }
        entries.clear();
        {
            for (String line : lines) {
                String[] p = line.split("\t", -1);
                if (p.length < 11 || p[0].startsWith("#")) continue;
                Entry e = new Entry(p[0]);
                e.name = p[1];
                e.lastSuccess = num(p[2]); e.lastErrorAt = num(p[3]); e.lastError = p[4];
                e.failures = (int) num(p[5]); e.pausedUntil = num(p[6]);
                e.etag = p[7]; e.lastModified = p[8]; e.lastChecked = num(p[9]);
                for (String dc : p[10].split(",")) {
                    String[] kv = dc.split(":");
                    if (kv.length == 2) try { e.addedPerDay.put(LocalDate.parse(kv[0]), Integer.parseInt(kv[1])); } catch (RuntimeException ignored) { }
                }
                entries.put(e.url, e);
            }
        }
        return this;
    }

    public synchronized void save() throws IOException {
        if (unreadable) load();
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, file);
        StringBuilder sb = new StringBuilder("# url\tname\tlastSuccess\tlastErrorAt\tlastError\tfailures\tpausedUntil\tetag\tlastModified\tlastChecked\taddedPerDay\n");
        for (Entry e : entries.values()) {
            StringBuilder days = new StringBuilder();
            for (var d : e.addedPerDay.entrySet()) {
                if (days.length() > 0) days.append(',');
                days.append(d.getKey()).append(':').append(d.getValue());
            }
            sb.append(String.join("\t", e.url, clean(e.name), Long.toString(e.lastSuccess), Long.toString(e.lastErrorAt),
                    clean(e.lastError), Integer.toString(e.failures), Long.toString(e.pausedUntil), clean(e.etag),
                    clean(e.lastModified), Long.toString(e.lastChecked), days)).append('\n');
        }
        com.linkeddesk.app.SafeFiles.write(file, sb.toString());
    }

    public synchronized Entry get(String url) {
        return entries.computeIfAbsent(url, Entry::new);
    }

    /** The entry for this feed, or null if it has never been downloaded. */
    public synchronized Entry find(String url) { return entries.get(url); }

    /** Should this feed be downloaded now? Paused feeds are skipped until their week is up. */
    public synchronized boolean isDue(FeedSource f, long now) {
        Entry e = entries.get(f.url());
        return e == null || e.pausedUntil <= now;
    }

    public synchronized void recordSuccess(FeedSource f, int added, boolean notModified, String etag, String lastModified, long now) {
        Entry e = get(f.url());
        e.name = f.name();
        e.lastChecked = now;
        e.lastSuccess = now;
        e.failures = 0;
        e.pausedUntil = 0;
        if (etag != null) e.etag = etag;
        if (lastModified != null) e.lastModified = lastModified;
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        e.addedPerDay.merge(day, added, Integer::sum);
        while (e.addedPerDay.size() > KEEP_DAYS) e.addedPerDay.pollFirstEntry();
    }

    public synchronized void recordFailure(FeedSource f, String error, long now) {
        Entry e = get(f.url());
        e.name = f.name();
        e.lastChecked = now;
        e.lastErrorAt = now;
        e.lastError = error == null ? "Unknown error" : error;
        e.failures++;
        if (e.failures >= PAUSE_AFTER) e.pausedUntil = now + PAUSE_MS;
    }

    /** "Retry now": un-pause so the next download tries the feed again. */
    public synchronized void resume(String url) {
        Entry e = entries.get(url);
        if (e != null) e.pausedUntil = 0;
    }

    public synchronized void forget(String url) { entries.remove(url); }

    public synchronized List<Entry> entries() { return new ArrayList<>(entries.values()); }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
