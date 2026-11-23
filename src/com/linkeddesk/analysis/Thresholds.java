package com.linkeddesk.analysis;

import com.linkeddesk.model.Category;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * How similar articles must be to count as the same event, and how much stories must share to be linked,
 * per desk. Kept in calibration.properties; Tools > Calibrate linking finds the best values.
 */
public final class Thresholds {
    /** Fallbacks for a damaged calibration.properties. */
    public static final double DEFAULT_SAME_EVENT = 0.36;
    public static final double DEFAULT_LINK = 0.10;
    public static final double DEFAULT_LINEAGE = 0.30;

    /**
     * Shipped per-desk values, calibrated on the labelled real-news sample (labelled-week.tsv) with
     * Tools > Calibrate linking: World and Society group slightly more readily; Society links more strictly
     * (disasters and climate stories share many common words).
     */
    private static final Map<Category, double[]> SHIPPED = Map.of(
            Category.WORLD, new double[]{0.34, 0.08},
            Category.FINANCE, new double[]{0.36, 0.08},
            Category.POLITICS, new double[]{0.36, 0.08},
            Category.SOCIETY, new double[]{0.34, 0.14},
            Category.TECH, new double[]{0.36, 0.08});

    private final Map<Category, Double> sameEvent = new EnumMap<>(Category.class);
    private final Map<Category, Double> link = new EnumMap<>(Category.class);
    private double lineage = DEFAULT_LINEAGE;

    public static Thresholds defaults() { return new Thresholds(); }

    public Thresholds() {
        for (Category c : Category.values()) { sameEvent.put(c, SHIPPED.get(c)[0]); link.put(c, SHIPPED.get(c)[1]); }
    }

    /** One value for every desk (the old single setting). */
    public static Thresholds uniform(double same, double link) { return new Thresholds().withAll(same, link); }

    public Thresholds copy() {
        Thresholds t = new Thresholds();
        t.sameEvent.putAll(sameEvent);
        t.link.putAll(link);
        t.lineage = lineage;
        return t;
    }

    public double sameEvent(Category c) { return sameEvent.get(c); }
    public double link(Category c) { return link.get(c); }
    public double lineage() { return lineage; }

    public Thresholds withSameEvent(Category c, double v) { Thresholds t = copy(); t.sameEvent.put(c, round(v)); return t; }
    public Thresholds withLink(Category c, double v) { Thresholds t = copy(); t.link.put(c, round(v)); return t; }
    public Thresholds withLineage(double v) { Thresholds t = copy(); t.lineage = round(v); return t; }
    public Thresholds withAll(double same, double lnk) {
        Thresholds t = copy();
        for (Category c : Category.values()) { t.sameEvent.put(c, round(same)); t.link.put(c, round(lnk)); }
        return t;
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    public boolean isDefault() { return equals(defaults()); }

    @Override public boolean equals(Object o) {
        return o instanceof Thresholds t && t.sameEvent.equals(sameEvent) && t.link.equals(link) && t.lineage == lineage;
    }
    @Override public int hashCode() { return sameEvent.hashCode() * 31 + link.hashCode(); }

    public static Thresholds load(Path file) throws IOException {
        Thresholds t = new Thresholds();
        if (!Files.exists(file)) return t;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        for (Category c : Category.values()) {
            t.sameEvent.put(c, num(p, "sameEvent." + c.name(), SHIPPED.get(c)[0], 0.05, 0.95));
            t.link.put(c, num(p, "link." + c.name(), SHIPPED.get(c)[1], 0.02, 0.90));
        }
        t.lineage = num(p, "lineage", DEFAULT_LINEAGE, 0.05, 0.95);
        return t;
    }

    private static double num(Properties p, String key, double def, double min, double max) {
        try {
            double v = Double.parseDouble(p.getProperty(key, String.valueOf(def)).strip());
            return v < min || v > max ? def : v;
        } catch (NumberFormatException e) { return def; }
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        for (Category c : Category.values()) {
            p.setProperty("sameEvent." + c.name(), String.valueOf(sameEvent.get(c)));
            p.setProperty("link." + c.name(), String.valueOf(link.get(c)));
        }
        p.setProperty("lineage", String.valueOf(lineage));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "The Linked Desk: grouping and linking thresholds per desk (Tools > Calibrate linking)");
        }
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values())
            sb.append(c.label()).append(": same event ").append(sameEvent.get(c)).append(", link ").append(link.get(c)).append("; ");
        return sb.append("storyline ").append(lineage).toString();
    }
}
