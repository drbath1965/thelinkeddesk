package com.linkeddesk.app;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/** User settings, stored in settings.properties next to the data folder. */
public final class Settings {
    private final Properties p = new Properties();

    public static Settings load() {
        Settings s = new Settings();
        if (Files.exists(AppPaths.settingsFile())) {
            try (Reader r = Files.newBufferedReader(AppPaths.settingsFile(), StandardCharsets.UTF_8)) {
                s.p.load(r);
            } catch (IOException ignored) { /* fall back to defaults */ }
        }
        return s;
    }

    /** Re-reads the file so values written by another process (the daily task) are seen. */
    public synchronized void reload() {
        Settings fresh = load();
        p.clear();
        p.putAll(fresh.p);
    }

    public synchronized void save() {
        try (Writer w = Files.newBufferedWriter(AppPaths.settingsFile(), StandardCharsets.UTF_8)) {
            p.store(w, "The Linked Desk settings");
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }

    public synchronized LocalTime dailyTime() {
        try {
            return LocalTime.parse(p.getProperty("dailyTime", "06:30"));
        } catch (DateTimeParseException e) {
            return LocalTime.of(6, 30);
        }
    }
    public synchronized void setDailyTime(LocalTime t) { p.setProperty("dailyTime", t.toString()); }

    public synchronized boolean dailyEnabled() { return Boolean.parseBoolean(p.getProperty("dailyEnabled", "true")); }
    public synchronized void setDailyEnabled(boolean b) { p.setProperty("dailyEnabled", Boolean.toString(b)); }

    public synchronized boolean fetchOnStart() { return Boolean.parseBoolean(p.getProperty("fetchOnStart", "true")); }
    public synchronized void setFetchOnStart(boolean b) { p.setProperty("fetchOnStart", Boolean.toString(b)); }

    public synchronized long lastFetch() {
        try { return Long.parseLong(p.getProperty("lastFetch", "0")); } catch (NumberFormatException e) { return 0; }
    }
    public synchronized void setLastFetch(long t) { p.setProperty("lastFetch", Long.toString(t)); }

    // ---- API key: stored encrypted (see SecretStore); a plain-text key from v1.0 is converted on first read.
    private static SecretStore secrets = new SecretStore();
    private transient String keyCacheFor, keyCache;

    static void useSecretStore(SecretStore s) { secrets = s; }

    public synchronized String apiKey() {
        String plain = p.getProperty("anthropicApiKey", "").trim();
        if (!plain.isEmpty()) {                       // migrate v1.0 plain-text key
            setApiKey(plain);
            save();
            return plain;
        }
        String enc = p.getProperty("anthropicApiKey.enc", "");
        if (enc.isEmpty()) return "";
        if (enc.equals(keyCacheFor)) return keyCache;
        try {
            keyCache = secrets.unprotect(enc);
            keyCacheFor = enc;
            return keyCache;
        } catch (Exception e) {
            Logs.error("Could not decrypt the saved API key (it may have been saved under another Windows user)", e);
            return "";
        }
    }

    public synchronized void setApiKey(String k) {
        String key = k == null ? "" : k.trim();
        p.remove("anthropicApiKey");
        if (key.isEmpty()) { p.remove("anthropicApiKey.enc"); keyCacheFor = null; return; }
        try {
            String enc = secrets.protect(key);
            p.setProperty("anthropicApiKey.enc", enc);
            keyCacheFor = enc;
            keyCache = key;
        } catch (Exception e) {
            Logs.error("Could not encrypt the API key; it was not saved", e);
            throw new IllegalStateException("The API key couldn't be encrypted, so it wasn't saved: " + e.getMessage(), e);
        }
    }

    /** The email password, encrypted the same way as the API key. */
    public synchronized String smtpPassword() {
        String enc = p.getProperty("smtpPassword.enc", "");
        if (enc.isEmpty()) return "";
        try { return secrets.unprotect(enc); }
        catch (Exception e) { Logs.error("Could not decrypt the saved email password", e); return ""; }
    }

    public synchronized void setSmtpPassword(String pw) {
        if (pw == null || pw.isEmpty()) { p.remove("smtpPassword.enc"); return; }
        try { p.setProperty("smtpPassword.enc", secrets.protect(pw)); }
        catch (Exception e) { throw new IllegalStateException("The email password couldn't be encrypted, so it wasn't saved: " + e.getMessage(), e); }
    }

    public synchronized boolean hasApiKey() {
        return !p.getProperty("anthropicApiKey", "").isBlank() || !p.getProperty("anthropicApiKey.enc", "").isBlank();
    }

    public synchronized String model() { return p.getProperty("claudeModel", "claude-sonnet-5-5").trim(); }
    public synchronized void setModel(String m) { p.setProperty("claudeModel", m == null || m.isBlank() ? "claude-sonnet-5-5" : m.trim()); }

    /** How many days of history the analysis covers by default (0 = everything). */
    /** Most to spend on Claude per calendar month, in US dollars (0 = Claude features off). */
    public synchronized double claudeBudget() { return dbl("claudeBudget", 5.0); }
    public synchronized void setClaudeBudget(double d) { p.setProperty("claudeBudget", Double.toString(Math.max(0, d))); }

    /** After the daily download: Claude writes the digest. */
    public synchronized boolean claudeDigest() { return Boolean.parseBoolean(p.getProperty("claudeDigest", "true")); }
    public synchronized void setClaudeDigest(boolean b) { p.setProperty("claudeDigest", Boolean.toString(b)); }

    /** After the daily download: Claude checks this many of the day's new links (0 = off). */
    public synchronized int claudeCheckLinks() {
        try { return Math.max(0, Math.min(50, Integer.parseInt(p.getProperty("claudeCheckLinks", "10").trim()))); }
        catch (NumberFormatException e) { return 10; }
    }
    public synchronized void setClaudeCheckLinks(int n) { p.setProperty("claudeCheckLinks", Integer.toString(Math.max(0, Math.min(50, n)))); }

    /** Your own prices ($ per million tokens, input and output); 0 = the built-in list price for the model. */
    public synchronized double priceIn() { return dbl("claudePriceIn", 0); }
    public synchronized double priceOut() { return dbl("claudePriceOut", 0); }
    public synchronized void setPrices(double in, double out) {
        p.setProperty("claudePriceIn", Double.toString(Math.max(0, in)));
        p.setProperty("claudePriceOut", Double.toString(Math.max(0, out)));
    }

    private double dbl(String key, double def) {
        try { return Double.parseDouble(p.getProperty(key, Double.toString(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Notifications for new watchlist matches after a download. */
    public synchronized boolean notifications() { return Boolean.parseBoolean(p.getProperty("notifications", "true")); }
    public synchronized void setNotifications(boolean b) { p.setProperty("notifications", Boolean.toString(b)); }

    /** Also notify about new links between desks. */
    public synchronized boolean notifyLinks() { return Boolean.parseBoolean(p.getProperty("notifyLinks", "false")); }
    public synchronized void setNotifyLinks(boolean b) { p.setProperty("notifyLinks", Boolean.toString(b)); }

    /** Quiet hours (no pop-ups), e.g. 22:00-07:00; null when not set. */
    public synchronized LocalTime quietFrom() { return time("quietFrom"); }
    public synchronized LocalTime quietTo() { return time("quietTo"); }
    public synchronized void setQuietHours(LocalTime from, LocalTime to) {
        if (from == null || to == null) { p.remove("quietFrom"); p.remove("quietTo"); return; }
        p.setProperty("quietFrom", from.toString());
        p.setProperty("quietTo", to.toString());
    }

    private LocalTime time(String key) {
        try { String v = p.getProperty(key); return v == null || v.isBlank() ? null : LocalTime.parse(v.trim()); }
        catch (RuntimeException e) { return null; }
    }

    // ---- email digest (ALR-F04)
    public synchronized boolean emailEnabled() { return Boolean.parseBoolean(p.getProperty("emailEnabled", "false")); }
    public synchronized void setEmailEnabled(boolean b) { p.setProperty("emailEnabled", Boolean.toString(b)); }
    public synchronized String smtpHost() { return p.getProperty("smtpHost", "").trim(); }
    public synchronized int smtpPort() { try { return Integer.parseInt(p.getProperty("smtpPort", "587").trim()); } catch (NumberFormatException e) { return 587; } }
    /** STARTTLS, SSL or NONE. */
    public synchronized String smtpSecurity() { return p.getProperty("smtpSecurity", "STARTTLS").trim(); }
    public synchronized String smtpUser() { return p.getProperty("smtpUser", "").trim(); }
    public synchronized String emailFrom() { return p.getProperty("emailFrom", "").trim(); }
    public synchronized String emailTo() { return p.getProperty("emailTo", "").trim(); }
    public synchronized String emailLastSent() { return p.getProperty("emailLastSent", "").trim(); }
    public synchronized void setEmailLastSent(String day) { p.setProperty("emailLastSent", day); }
    public synchronized void setSmtp(String host, int port, String security, String user, String from, String to) {
        p.setProperty("smtpHost", host.trim());
        p.setProperty("smtpPort", Integer.toString(port));
        p.setProperty("smtpSecurity", security);
        p.setProperty("smtpUser", user.trim());
        p.setProperty("emailFrom", from.trim());
        p.setProperty("emailTo", to.trim());
    }

    /** Show the briefing when the app opens and after each daily download. */
    public synchronized boolean showBriefing() { return Boolean.parseBoolean(p.getProperty("showBriefing", "true")); }
    public synchronized void setShowBriefing(boolean b) { p.setProperty("showBriefing", Boolean.toString(b)); }

    /** "light" or "dark". */
    public synchronized String theme() { return p.getProperty("theme", "light").trim(); }
    public synchronized void setTheme(String t) { p.setProperty("theme", t); }

    /** Text size in percent of normal (80-200). */
    public synchronized int textSize() {
        try { return Math.max(80, Math.min(200, Integer.parseInt(p.getProperty("textSize", "100").trim()))); }
        catch (NumberFormatException e) { return 100; }
    }
    public synchronized void setTextSize(int pct) { p.setProperty("textSize", Integer.toString(Math.max(80, Math.min(200, pct)))); }

    public synchronized int windowDays() {
        try { return Integer.parseInt(p.getProperty("windowDays", "7")); } catch (NumberFormatException e) { return 7; }
    }
    public synchronized void setWindowDays(int d) { p.setProperty("windowDays", Integer.toString(d)); }

    /** Last 10 searches, newest first. */
    public synchronized java.util.List<String> recentSearches() {
        String v = p.getProperty("recentSearches", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : v.split("\u001F")) if (!x.isBlank()) out.add(x);
        return out;
    }

    public synchronized void addRecentSearch(String q) {
        java.util.List<String> list = recentSearches();
        list.removeIf(x -> x.equalsIgnoreCase(q));
        list.add(0, q);
        while (list.size() > 10) list.remove(list.size() - 1);
        p.setProperty("recentSearches", String.join("\u001F", list));
    }
}
