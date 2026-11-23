package com.linkeddesk.schedule;

import com.linkeddesk.Check;
import com.linkeddesk.app.Settings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** OPS-F02: when does the in-app daily download fire? Driven minute by minute with a fake clock. */
public final class DailySchedulerTest {
    private static final ZoneId CHI = ZoneId.of("America/Chicago");

    /** A clock the test moves by hand. */
    static final class FakeClock extends Clock {
        Instant now;
        FakeClock(Instant start) { now = start; }
        @Override public ZoneId getZone() { return CHI; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static Instant at(String local) { return LocalDateTime.parse(local).atZone(CHI).toInstant(); }

    public static void run(Check c) throws Exception {
        c.suite("Daily download rule (OPS-F02)");
        LocalTime six30 = LocalTime.of(6, 30);
        Instant never = Instant.EPOCH;
        Instant yesterday = at("2026-11-22T06:31:00");

        c.that(!DailyScheduler.isDue(at("2026-11-23T09:00:00"), CHI, false, six30, yesterday, never), "off when daily downloads are turned off");
        c.that(!DailyScheduler.isDue(at("2026-11-23T06:29:00"), CHI, true, six30, yesterday, never), "waits before the set time");
        c.that(DailyScheduler.isDue(at("2026-11-23T06:30:00"), CHI, true, six30, yesterday, never), "fires at the set time");
        c.that(DailyScheduler.isDue(at("2026-11-23T14:00:00"), CHI, true, six30, yesterday, never), "fires later in the day if missed");
        c.that(!DailyScheduler.isDue(at("2026-11-23T14:00:00"), CHI, true, six30, at("2026-11-23T06:31:00"), never), "only once per day");
        c.that(!DailyScheduler.isDue(at("2026-11-23T14:00:00"), CHI, true, six30, at("2026-11-23T05:00:00"), never), "an early manual download counts for the day");
        c.that(DailyScheduler.isDue(at("2026-11-24T02:00:00"), CHI, true, six30, at("2026-11-22T06:31:00"), never), "catches up after midnight if a whole day was missed");
        c.that(!DailyScheduler.isDue(at("2026-11-23T07:00:00"), CHI, true, six30, yesterday, at("2026-11-23T06:45:00")), "waits 30 min after a failed attempt");
        c.that(DailyScheduler.isDue(at("2026-11-23T07:16:00"), CHI, true, six30, yesterday, at("2026-11-23T06:45:00")), "retries after 30 min");
        c.that(DailyScheduler.isDue(at("2026-11-23T09:00:00"), CHI, true, six30, never, never), "first ever run downloads straight away");
        c.that(DailyScheduler.isDue(at("2026-03-08T06:30:00"), CHI, true, six30, at("2026-03-07T06:30:00"), never), "works on the daylight-saving change day");

        // ---- Simulate a week, ticking once a minute like the app does.
        String home = System.getProperty("java.io.tmpdir") + "/ld-sched-" + System.nanoTime();
        System.setProperty("linkeddesk.home", home);
        Settings s = Settings.load();
        s.setDailyEnabled(true);
        s.setDailyTime(six30);
        s.setLastFetch(at("2026-11-22T06:30:00").toEpochMilli());
        s.save();

        FakeClock clock = new FakeClock(at("2026-11-23T00:00:00"));
        List<LocalDateTime> runs = new ArrayList<>();
        boolean[] failOnTue = {true};
        Runnable fetch = () -> {
            LocalDateTime t = LocalDateTime.ofInstant(clock.instant(), CHI);
            runs.add(t);
            // Tuesday's first attempt fails (no internet); every other attempt succeeds.
            if (t.getDayOfMonth() == 24 && failOnTue[0]) { failOnTue[0] = false; return; }
            s.setLastFetch(clock.millis());
            s.save();
        };
        DailyScheduler sched = new DailyScheduler(s, fetch, clock);
        for (int day = 0; day < 7; day++) {
            for (int m = 0; m < 1440; m++) {
                LocalDateTime t = LocalDateTime.ofInstant(clock.instant(), CHI);
                boolean asleep = t.getDayOfMonth() == 26 && t.getHour() >= 5 && t.getHour() < 10; // PC asleep Thu 05:00–10:00
                if (!asleep) sched.check();
                clock.advance(Duration.ofMinutes(1));
            }
        }
        List<String> got = runs.stream().map(r -> r.toLocalDate() + " " + r.toLocalTime()).toList();
        System.out.println("     attempts: " + got);
        c.eq(8, runs.size(), "7 days give 7 downloads plus 1 retry");
        c.eq("2026-11-23 06:30", got.get(0), "Monday at 06:30");
        c.eq("2026-11-24 06:30", got.get(1), "Tuesday at 06:30 (fails)");
        c.eq("2026-11-24 07:00", got.get(2), "Tuesday retry 30 minutes later succeeds");
        c.eq("2026-11-25 06:30", got.get(3), "Wednesday at 06:30");
        c.eq("2026-11-26 10:00", got.get(4), "Thursday: PC asleep at 06:30, downloads on wake at 10:00");
        c.eq("2026-11-29 06:30", got.get(7), "Sunday at 06:30");
    }
}
