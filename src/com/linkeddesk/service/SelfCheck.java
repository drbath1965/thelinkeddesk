package com.linkeddesk.service;

import com.linkeddesk.alerts.Tray;
import com.linkeddesk.app.AppPaths;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.schedule.WindowsTask;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Check this PC (OPS-F06): looks at everything the app needs (Java, the daily task, the saved key, alerts, feeds,
 * the data folder, the search index, backups, the screen) and says what's fine, what needs a look, and how to fix it.
 * {@link #gather} reads the PC; {@link #evaluate} turns those facts into results and is pure, so it can be tested.
 */
public final class SelfCheck {
    private SelfCheck() {}

    public enum Status { OK, WARN, FAIL, INFO }

    public record Check(String name, Status status, String detail, String fix) {
        public String icon() { return switch (status) { case OK -> "✔"; case WARN -> "!"; case FAIL -> "✘"; case INFO -> "i"; }; }
    }

    /** Everything the checks look at. Public fields so tests can set them directly. */
    public static final class Facts {
        public long now = System.currentTimeMillis();
        public String appVersion = AppPaths.VERSION;
        public int javaFeature = Runtime.version().feature();
        public String javaHome = System.getProperty("java.home", "");
        public boolean windows = WindowsTask.supported();
        public String runningFrom = "";
        public Map<String, String> task;            // schtasks fields; null = no task
        public String taskError;                    // why the task couldn't be read
        public Boolean taskRunsThisCopy;
        public boolean hasKey, keyReadable;
        public String keyStart = "";
        public boolean emailEnabled, emailConfigured, emailPasswordReadable;
        public boolean traySupported;
        public Path dataDir;
        public boolean dataWritable;
        public long freeBytes = -1;
        public long lastFetch;
        public int feeds, failing, paused;
        public List<String> failingNames = new ArrayList<>();
        public long indexFiles, staleMonths, months;
        public String backupFolder = "";
        public boolean backupFolderExists, backupWeekly;
        public long backupLast;
        public int screenW, screenH, windowW, windowH;
        public double scale = 1;
        public String latestVersion, updateError;  // from the update check, if it ran
        public List<String> fileProblems = new ArrayList<>(), errorsThisWeek = new ArrayList<>();
        public boolean settingsUnreadable;
    }

    public static Facts gather(NewsService s, Window window) {
        Facts f = new Facts();
        var st = s.settings();
        st.reload();
        try { f.runningFrom = String.valueOf(WindowsTask.jarPath()); } catch (Exception ignored) { }
        if (f.windows) {
            try { f.task = WindowsTask.fields(WindowsTask.query()); }
            catch (Exception e) { f.task = null; f.taskError = e.getMessage(); }
            f.taskRunsThisCopy = WindowsTask.runsThisCopy();
        }
        f.fileProblems = com.linkeddesk.app.SafeFiles.problems();
        f.errorsThisWeek = com.linkeddesk.app.Logs.errorsSince(java.time.LocalDateTime.now().minusDays(7));
        f.settingsUnreadable = st.unreadable();
        f.hasKey = st.hasApiKey();
        String k = f.hasKey ? st.apiKey() : "";
        f.keyReadable = !k.isEmpty();
        f.keyStart = k.length() >= 7 ? k.substring(0, 7) : k;
        f.emailEnabled = st.emailEnabled();
        f.emailConfigured = !st.smtpHost().isBlank() && !st.emailTo().isBlank();
        f.emailPasswordReadable = !st.smtpPassword().isEmpty() || st.smtpUser().isBlank();
        f.traySupported = Tray.supported();
        f.dataDir = AppPaths.dataDir();
        try {
            Files.createDirectories(f.dataDir);
            Path probe = Files.createTempFile(f.dataDir, ".check", ".tmp");
            Files.delete(probe);
            f.dataWritable = true;
            f.freeBytes = Files.getFileStore(f.dataDir).getUsableSpace();
        } catch (Exception e) { f.dataWritable = false; }
        f.lastFetch = st.lastFetch();
        long now = f.now;
        for (FeedHealth.Entry e : s.health().entries()) {
            f.feeds++;
            switch (e.state(now)) {
                case FAILING -> { f.failing++; if (f.failingNames.size() < 5) f.failingNames.add(e.name.isBlank() ? e.url : e.name); }
                case PAUSED -> f.paused++;
                default -> { }
            }
        }
        try {
            var idx = s.store().index();
            f.indexFiles = idx.stats()[0];
            f.staleMonths = idx.stale().size();
            f.months = s.store().files().size();
        } catch (Exception ignored) { }
        f.backupFolder = st.backupFolder();
        f.backupFolderExists = !f.backupFolder.isBlank() && Files.isDirectory(Path.of(f.backupFolder));
        f.backupWeekly = st.backupWeekly();
        f.backupLast = st.backupLast();
        try {
            GraphicsConfiguration gc = window != null && window.getGraphicsConfiguration() != null ? window.getGraphicsConfiguration()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            f.screenW = b.width - in.left - in.right;
            f.screenH = b.height - in.top - in.bottom;
            f.scale = gc.getDefaultTransform().getScaleX();
            if (window != null) { f.windowW = window.getWidth(); f.windowH = window.getHeight(); }
        } catch (Exception ignored) { }
        return f;
    }

    public static List<Check> evaluate(Facts f) {
        List<Check> out = new ArrayList<>();
        DateTimeFormatter when = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a").withZone(ZoneId.systemDefault());

        // Java and this copy
        if (f.javaFeature >= 21) out.add(new Check("Java", Status.OK, "Java " + f.javaFeature + " (" + f.javaHome + ").", ""));
        else out.add(new Check("Java", Status.FAIL, "Java " + f.javaFeature + " is too old; the app needs Java 21 or later.",
                "Install a current Java (for example Temurin 21 from adoptium.net) and start the app again."));
        out.add(new Check("App version", Status.INFO, "The Linked Desk " + f.appVersion + (f.runningFrom.isBlank() ? "" : ", running from " + f.runningFrom) + ".", ""));
        if (f.latestVersion != null) {
            if (UpdateCheck.compare(f.latestVersion, f.appVersion) > 0)
                out.add(new Check("Updates", Status.WARN, "Version " + f.latestVersion + " is available.", "Help > Check for updates… downloads it and restarts."));
            else out.add(new Check("Updates", Status.OK, "This is the newest version.", ""));
        } else if (f.updateError != null) out.add(new Check("Updates", Status.INFO, "Couldn't check for updates: " + f.updateError, ""));

        // Daily task
        if (!f.windows) out.add(new Check("Daily download task", Status.INFO, "Not on Windows, so there's no Task Scheduler task; the app downloads while it's open.", ""));
        else if (f.task == null) out.add(new Check("Daily download task", Status.WARN, "There's no daily download task, so news is only downloaded while the app is open.",
                "Tools > Settings > Schedule: set a time and choose Install task."));
        else {
            String status = f.task.getOrDefault("Status", ""), result = f.task.getOrDefault("Last Result", "");
            String next = f.task.getOrDefault("Next Run Time", "?"), last = f.task.getOrDefault("Last Run Time", "?");
            String d = "Next run " + next + "; last run " + last + (result.isBlank() ? "" : " (result " + result + ")") + ".";
            if (status.equalsIgnoreCase("Disabled"))
                out.add(new Check("Daily download task", Status.FAIL, "The task is disabled. " + d, "Tools > Settings > Schedule: choose Install task again (it re-enables it)."));
            else if (!result.isBlank() && !result.equals("0") && !result.equals("267011") && !result.equals("267009") && !result.equals("267045"))
                out.add(new Check("Daily download task", Status.WARN, "The last run ended with an error. " + d,
                        "View > Logs shows what went wrong. If the PC was asleep, the task runs when it wakes."));
            else out.add(new Check("Daily download task", Status.OK, d, ""));
            if (Boolean.FALSE.equals(f.taskRunsThisCopy))
                out.add(new Check("Task runs this copy", Status.WARN, "The task starts a different copy of the app than this one.",
                        "Tools > Settings > Schedule: choose Install task to point it at this copy."));
        }
        if (f.lastFetch == 0) out.add(new Check("Last download", Status.WARN, "News hasn't been downloaded yet.", "News > Download now."));
        else {
            long h = (f.now - f.lastFetch) / 3_600_000L;
            out.add(new Check("Last download", h > 36 ? Status.WARN : Status.OK, "Last download " + when.format(Instant.ofEpochMilli(f.lastFetch)) + " (" + ago(h) + ").",
                    h > 36 ? "News > Download now; then check the daily task above." : ""));
        }

        // Claude key
        if (!f.hasKey) out.add(new Check("Anthropic API key", Status.INFO, "No key saved, so Claude features (digest, questions, framing) are off.", "Tools > Settings > Claude: paste a key."));
        else if (!f.keyReadable) out.add(new Check("Anthropic API key", Status.FAIL, "A key is saved but can't be decrypted. Windows encrypts it for one user on one PC, so it may have been saved under another account.",
                "Tools > Settings > Claude: paste the key again."));
        else if (!f.keyStart.startsWith("sk-ant-")) out.add(new Check("Anthropic API key", Status.WARN, "The saved key doesn't start with sk-ant-, so it may be mistyped.", "Tools > Settings > Claude: paste the key again."));
        else out.add(new Check("Anthropic API key", Status.OK, "Saved and encrypted for this Windows user.", ""));

        // Alerts
        out.add(f.traySupported ? new Check("Notifications", Status.OK, "The tray icon and pop-up notifications are available.", "")
                : new Check("Notifications", Status.WARN, "This desktop has no system tray, so watch alerts can't pop up.", "Alerts still show in the Watchlist tab and by email."));
        if (f.emailEnabled) {
            if (!f.emailConfigured) out.add(new Check("Email", Status.FAIL, "Email is on but the mail server or recipient is missing.", "Tools > Settings > Alerts: fill them in, then Send test email now."));
            else if (!f.emailPasswordReadable) out.add(new Check("Email", Status.FAIL, "The email password can't be decrypted.", "Tools > Settings > Alerts: enter the password again."));
            else out.add(new Check("Email", Status.OK, "Set up. Use Send a test email below to be sure it arrives.", ""));
        } else out.add(new Check("Email", Status.INFO, "Email briefings and alerts are off.", ""));

        // Feeds
        if (f.feeds == 0) out.add(new Check("Feeds", Status.INFO, "No feeds have been checked yet.", "News > Download now."));
        else {
            int bad = f.failing + f.paused;
            Status st = bad == 0 ? Status.OK : bad * 5 > f.feeds ? Status.FAIL : Status.WARN;
            String d = f.feeds + " feeds; " + (bad == 0 ? "all working." : f.failing + " failing, " + f.paused + " paused"
                    + (f.failingNames.isEmpty() ? "." : " (" + String.join(", ", f.failingNames) + (f.failing > f.failingNames.size() ? ", …" : "") + ")."));
            out.add(new Check("Feeds", st, d, bad == 0 ? "" : st == Status.FAIL ? "Many feeds failing usually means no internet or a firewall/proxy blocking Java. Check the connection, then News > Download now."
                    : "Tools > Settings > Feeds shows each feed's last error; a feed that has moved can be removed or replaced."));
        }

        // Data, index, backup
        if (!f.dataWritable) out.add(new Check("Data folder", Status.FAIL, "Can't write to " + f.dataDir + ".", "Check the folder's permissions, or that a sync tool isn't locking it."));
        else if (f.freeBytes >= 0 && f.freeBytes < 500L * 1024 * 1024)
            out.add(new Check("Data folder", Status.WARN, f.dataDir + " has only " + (f.freeBytes >> 20) + " MB free.", "Free some disk space; Tools > Archive and backup can compress old months."));
        else out.add(new Check("Data folder", Status.OK, f.dataDir + (f.freeBytes >= 0 ? " (" + (f.freeBytes >> 30) + " GB free)." : "."), ""));
        if (f.months > 0) {
            if (f.indexFiles == 0) out.add(new Check("Search index", Status.WARN, "Not built yet, so searches scan every article.", "It builds in the background after a download; Tools > Archive and backup has Rebuild."));
            else if (f.staleMonths > 0) out.add(new Check("Search index", Status.INFO, f.staleMonths + " month(s) waiting to be indexed; this happens on its own after the next download.", ""));
            else out.add(new Check("Search index", Status.OK, "Up to date for all " + f.months + " month(s).", ""));
        }
        if (f.backupFolder.isBlank()) out.add(new Check("Backup", Status.WARN, "No backup folder chosen.", "Tools > Archive and backup: choose a folder (OneDrive or a USB drive) and turn on weekly backups."));
        else if (!f.backupFolderExists) out.add(new Check("Backup", Status.FAIL, "The backup folder " + f.backupFolder + " isn't there (unplugged drive?).", "Reconnect it, or choose another folder in Tools > Archive and backup."));
        else if (f.backupLast == 0) out.add(new Check("Backup", Status.WARN, "No backup has been made yet.", "Tools > Archive and backup > Back up now."));
        else {
            long days = (f.now - f.backupLast) / 86_400_000L;
            Status st = days > 14 ? Status.WARN : Status.OK;
            out.add(new Check("Backup", st, "Last backup " + (days == 0 ? "today" : days + " day(s) ago") + (f.backupWeekly ? "; weekly backups on." : "; weekly backups off."),
                    st == Status.WARN || !f.backupWeekly ? "Tools > Archive and backup: Back up now, and turn on weekly backups." : ""));
        }

        // Saved files and logged errors (OPS-F08, OPS-F09)
        if (f.settingsUnreadable) out.add(new Check("Saved files", Status.FAIL, "settings.properties can't be read, so settings aren't being saved (nothing has been lost).",
                "Close other programs that may lock it (a sync tool or antivirus), then restart the app."));
        else if (!f.fileProblems.isEmpty()) out.add(new Check("Saved files", Status.WARN, f.fileProblems.size() + (f.fileProblems.size() == 1 ? " read problem" : " read problems")
                + " since the app started; the latest: " + f.fileProblems.get(f.fileProblems.size() - 1) + ".",
                "Nothing was saved over. If it keeps happening, exclude the data folder from your antivirus or sync tool."));
        else out.add(new Check("Saved files", Status.OK, "Every file read and saved normally; a backup copy (.bak) is kept of each.", ""));
        if (!f.errorsThisWeek.isEmpty()) {
            java.util.Map<String, Integer> kinds = new java.util.LinkedHashMap<>();
            List<String> newest = new ArrayList<>(f.errorsThisWeek);
            Collections.reverse(newest);
            for (String e : newest) kinds.merge(e.length() > 70 ? e.substring(0, 70) + "…" : e, 1, Integer::sum);
            StringBuilder d = new StringBuilder(f.errorsThisWeek.size() + (f.errorsThisWeek.size() == 1 ? " error" : " errors") + " logged in the last 7 days, newest first: ");
            int i = 0;
            for (var k : kinds.entrySet()) { if (i++ == 3) { d.append("…"); break; } d.append(i > 1 ? "; " : "").append(k.getKey()).append(k.getValue() > 1 ? " (×" + k.getValue() + ")" : ""); }
            out.add(new Check("Errors this week", Status.INFO, d.toString(), "View > Logs > Errors has the details; Save report… and send it to have them looked at."));
        }

        // Screen
        if (f.screenW > 0) {
            String d = "Screen " + f.screenW + "×" + f.screenH + " at " + Math.round(f.scale * 100) + "% scaling"
                    + (f.windowW > 0 ? "; window " + f.windowW + "×" + f.windowH : "") + ".";
            boolean tooBig = f.windowW > f.screenW + 16 || f.windowH > f.screenH + 16;
            boolean cramped = f.screenW < 1100 || f.screenH < 650;
            if (tooBig) out.add(new Check("Screen and scaling", Status.WARN, d + " The window is larger than the screen.", "Double-click the title bar to fit it, or lower View > Text size."));
            else if (cramped) out.add(new Check("Screen and scaling", Status.WARN, d + " That's a small working area at this scaling.", "Lower Windows display scaling or View > Text size; Ctrl+wheel zooms just the news."));
            else out.add(new Check("Screen and scaling", Status.OK, d, ""));
        }
        return out;
    }

    private static String ago(long h) { return h < 1 ? "under an hour ago" : h == 1 ? "an hour ago" : h < 48 ? h + " hours ago" : (h / 24) + " days ago"; }

    public static String report(List<Check> checks, Facts f) {
        StringBuilder sb = new StringBuilder("The Linked Desk — Check this PC\n").append(Instant.ofEpochMilli(f.now)).append("\n\n");
        for (Check c : checks) {
            sb.append('[').append(c.status()).append("] ").append(c.name()).append(": ").append(c.detail()).append('\n');
            if (!c.fix().isBlank()) sb.append("    Fix: ").append(c.fix()).append('\n');
        }
        return sb.toString();
    }

    public static Status overall(List<Check> checks) {
        boolean warn = false;
        for (Check c : checks) { if (c.status() == Status.FAIL) return Status.FAIL; if (c.status() == Status.WARN) warn = true; }
        return warn ? Status.WARN : Status.OK;
    }
}
