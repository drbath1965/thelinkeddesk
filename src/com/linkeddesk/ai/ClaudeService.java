package com.linkeddesk.ai;

import com.linkeddesk.app.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Every call to Claude goes through here, so it stays within your monthly budget (AI-F05):
 * <ul>
 *   <li>Before a call, its cost is estimated (prompt length plus the longest possible answer); if that would take
 *       the month over the budget, the call isn't made and you get a clear message instead.</li>
 *   <li>After a call, the tokens Anthropic reports are priced and logged in claude-usage.tsv.</li>
 *   <li>Answers are cached in claude-cache.tsv: the same question is never sent twice.</li>
 * </ul>
 */
public final class ClaudeService {
    /** The monthly budget would be exceeded (or Claude is switched off). */
    public static final class BudgetException extends IllegalStateException {
        BudgetException(String m) { super(m); }
    }

    /** One logged call. */
    public record Call(long time, String feature, String model, long in, long out, double cost) {}

    private final Settings settings;
    private final Path usageFile, cacheFile;
    private final Function<Settings, ClaudeClient> clients;
    private final Map<String, String> cache = new HashMap<>();
    private boolean cacheLoaded;
    private int cacheHits;

    public ClaudeService(Settings settings, Path home) {
        this(settings, home, s -> new ClaudeClient(s.apiKey(), s.model()));
    }

    ClaudeService(Settings settings, Path home, Function<Settings, ClaudeClient> clients) {
        this.settings = settings;
        this.usageFile = home.resolve("claude-usage.tsv");
        this.cacheFile = home.resolve("claude-cache.tsv");
        this.clients = clients;
    }

    public boolean hasKey() { settings.reload(); return settings.hasApiKey(); }

    /** $ per million tokens (input, output) in use: your own prices if set, else the list price for the model. */
    public double[] prices() {
        double[] list = Pricing.of(settings.model());
        double in = settings.priceIn(), out = settings.priceOut();
        return new double[]{in > 0 ? in : list[0], out > 0 ? out : list[1]};
    }

    /**
     * Asks Claude, or answers from the cache. {@code feature} names what it's for in the usage log
     * ("explain", "digest", "check", "ask").
     */
    public synchronized String ask(String feature, String prompt, int maxTokens) throws Exception {
        settings.reload();
        String model = settings.model();
        String key = hash(feature + "\u0000" + model + "\u0000" + maxTokens + "\u0000" + prompt);
        loadCache();
        String hit = cache.get(key);
        if (hit != null) { cacheHits++; return hit; }
        if (!settings.hasApiKey()) throw new BudgetException("Add an Anthropic API key in Tools > Settings to use Claude.");
        double budget = settings.claudeBudget();
        if (budget <= 0) throw new BudgetException("Claude features are switched off (monthly budget set to $0 in Tools > Settings).");
        double[] price = prices();
        long estIn = prompt.length() / 3 + 50;
        double estimate = Pricing.cost(price, estIn, maxTokens);
        double spent = spentThisMonth();
        if (spent + estimate > budget) {
            YearMonth next = YearMonth.now().plusMonths(1);
            throw new BudgetException(String.format(
                    "Monthly Claude budget reached: $%.2f of $%.2f spent this month, and this request could cost up to $%.3f. "
                            + "Raise the budget in Tools > Settings, or wait until %s 1.",
                    spent, budget, estimate, next.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())));
        }
        ClaudeClient.Reply r = clients.apply(settings).send(prompt, maxTokens);
        double cost = Pricing.cost(price, r.inputTokens(), r.outputTokens());
        log(new Call(System.currentTimeMillis(), feature, model, r.inputTokens(), r.outputTokens(), cost));
        cache.put(key, r.text());
        appendCache(key, r.text());
        return r.text();
    }

    // ------------------------------------------------------------------ usage

    private synchronized void log(Call c) throws IOException {
        String line = c.time() + "\t" + c.feature() + "\t" + c.model() + "\t" + c.in() + "\t" + c.out() + "\t" + String.format(java.util.Locale.ROOT, "%.6f", c.cost()) + "\n";
        Files.writeString(usageFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Calls made in the given month. */
    public synchronized List<Call> calls(YearMonth month) {
        List<Call> out = new ArrayList<>();
        if (!Files.exists(usageFile)) return out;
        try {
            for (String l : Files.readAllLines(usageFile, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t");
                if (p.length < 6) continue;
                try {
                    long t = Long.parseLong(p[0]);
                    if (!YearMonth.from(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())).equals(month)) continue;
                    out.add(new Call(t, p[1], p[2], Long.parseLong(p[3]), Long.parseLong(p[4]), Double.parseDouble(p[5])));
                } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    public double spentThisMonth() {
        double s = 0;
        for (Call c : calls(YearMonth.now())) s += c.cost();
        return s;
    }

    /** This month's cost per feature. */
    public Map<String, double[]> byFeature() {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Call c : calls(YearMonth.now())) {
            double[] v = m.computeIfAbsent(c.feature(), k -> new double[2]);
            v[0]++;
            v[1] += c.cost();
        }
        return m;
    }

    public int cacheHits() { return cacheHits; }

    public synchronized int cachedAnswers() { loadCache(); return cache.size(); }

    /** "This month: $1.23 of $5.00 (25%) · 45 requests". */
    public String summary() {
        settings.reload();
        List<Call> calls = calls(YearMonth.now());
        double s = 0;
        for (Call c : calls) s += c.cost();
        double b = settings.claudeBudget();
        return String.format("This month: $%.2f of $%.2f%s · %d request%s", s, b, b > 0 ? String.format(" (%.0f%%)", 100 * s / b) : "",
                calls.size(), calls.size() == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------ cache

    private void loadCache() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        if (!Files.exists(cacheFile)) return;
        try {
            for (String l : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                int t = l.indexOf('\t'), t2 = t < 0 ? -1 : l.indexOf('\t', t + 1);
                if (t2 < 0) continue;
                try { cache.put(l.substring(0, t), new String(Base64.getDecoder().decode(l.substring(t2 + 1)), StandardCharsets.UTF_8)); }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void appendCache(String key, String text) throws IOException {
        String line = key + "\t" + System.currentTimeMillis() + "\t" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\n";
        Files.writeString(cacheFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
