package com.linkeddesk.schedule;

import com.linkeddesk.app.Settings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * While the window is open, checks once a minute whether a download is due and triggers it.
 *
 * A download is due when daily downloads are on and either
 *  - it is at or after today's set time and there has been no download yet today, or
 *  - the last download is more than {@link #CATCH_UP} old (e.g. the PC slept through the set time
 *    and woke after midnight).
 * After a failed attempt it waits {@link #RETRY_AFTER} before trying again, instead of every minute.
 */
public final class DailyScheduler {
    static final Duration CATCH_UP = Duration.ofHours(26);
    static final Duration RETRY_AFTER = Duration.ofMinutes(30);

    private final Settings settings;
    private final Runnable fetch;
    private final Clock clock;
    private volatile Instant lastAttempt = Instant.EPOCH;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daily-scheduler");
        t.setDaemon(true);
        return t;
    });

    public DailyScheduler(Settings settings, Runnable fetch) {
        this(settings, fetch, Clock.systemDefaultZone());
    }

    DailyScheduler(Settings settings, Runnable fetch, Clock clock) {
        this.settings = settings;
        this.fetch = fetch;
        this.clock = clock;
    }

    public void start() {
        exec.scheduleAtFixedRate(this::check, 1, 1, TimeUnit.MINUTES);
    }

    /** One scheduler tick. Package-visible so tests can drive it with a fake clock. */
    void check() {
        try {
            settings.reload();
            Instant now = clock.instant();
            if (isDue(now, clock.getZone(), settings.dailyEnabled(), settings.dailyTime(),
                    Instant.ofEpochMilli(settings.lastFetch()), lastAttempt,
                    com.linkeddesk.feeds.DownloadVolume.of(settings).everyHours())) {
                lastAttempt = now;
                fetch.run();
            }
        } catch (RuntimeException e) {
            System.err.println("Scheduler: " + e.getMessage());
        }
    }

    /** Pure decision rule, so it can be tested for any date, time and history. */
    public static boolean isDue(Instant now, ZoneId zone, boolean enabled, LocalTime dailyTime,
                                Instant lastFetch, Instant lastAttempt) {
        return isDue(now, zone, enabled, dailyTime, lastFetch, lastAttempt, 0);
    }

    /** As above, and also due once the last download is {@code everyHours} old (when above 0; FET-F10). */
    public static boolean isDue(Instant now, ZoneId zone, boolean enabled, LocalTime dailyTime,
                                Instant lastFetch, Instant lastAttempt, int everyHours) {
        if (!enabled) return false;
        if (everyHours > 0 && Duration.between(lastAttempt, now).compareTo(Duration.ofMinutes(Math.min(30, everyHours * 30L))) >= 0
                && Duration.between(lastFetch, now).compareTo(Duration.ofHours(everyHours).minusMinutes(2)) >= 0) return true;
        if (Duration.between(lastAttempt, now).compareTo(RETRY_AFTER) < 0) return false;
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        boolean doneToday = !LocalDateTime.ofInstant(lastFetch, zone).toLocalDate().isBefore(local.toLocalDate());
        if (Duration.between(lastFetch, now).compareTo(CATCH_UP) > 0) return true;
        return !local.toLocalTime().isBefore(dailyTime) && !doneToday;
    }

    /** True when the last download is more than 20 hours old (used for the download on start-up). */
    public static boolean isStale(Settings s) {
        return System.currentTimeMillis() - s.lastFetch() > 20L * 3600_000;
    }
}
