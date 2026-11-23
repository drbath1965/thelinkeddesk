package com.linkeddesk.ai;

import java.util.Locale;
import java.util.Map;

/**
 * Anthropic list prices in US dollars per million tokens (input, output), from
 * platform.claude.com/docs/en/about-claude/pricing (Nov 2026). Prices can change: Settings lets you enter your own.
 */
public final class Pricing {
    private Pricing() {}

    /** model id prefix -> {input, output} $ per million tokens */
    private static final Map<String, double[]> LIST = Map.ofEntries(
            Map.entry("claude-fable-5-2", new double[]{8, 40}),
            Map.entry("claude-mythos-5-2", new double[]{8, 40}),
            Map.entry("claude-opus-5-5", new double[]{4, 20}),
            Map.entry("claude-sonnet-5-5", new double[]{2, 10}),
            Map.entry("claude-haiku-5-5", new double[]{0.10, 0.50}),
            Map.entry("claude-fable-5-1", new double[]{10, 50}),
            Map.entry("claude-fable-5", new double[]{10, 50}),
            Map.entry("claude-opus-5", new double[]{5, 25}),
            Map.entry("claude-sonnet-5", new double[]{2, 10}),
            Map.entry("claude-opus-4", new double[]{5, 25}),
            Map.entry("claude-sonnet-4", new double[]{3, 15}),
            Map.entry("claude-haiku-4-5", new double[]{1, 5}));

    /** Fallback for a model not in the list: the most expensive common tier, so the budget errs on the safe side. */
    static final double[] UNKNOWN = {8, 40};

    /** {input, output} $ per million tokens for this model (longest matching id wins). */
    public static double[] of(String model) {
        String m = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        String best = null;
        for (String k : LIST.keySet()) if (m.startsWith(k) && (best == null || k.length() > best.length())) best = k;
        return best == null ? UNKNOWN : LIST.get(best);
    }

    public static boolean known(String model) { return of(model) != UNKNOWN; }

    public static double cost(double[] price, long in, long out) { return (in * price[0] + out * price[1]) / 1_000_000.0; }
}
