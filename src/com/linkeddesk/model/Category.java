package com.linkeddesk.model;

import java.awt.Color;

/** The desks an article or story can belong to. */
public enum Category {
    WORLD("World", new Color(0x1F5FA8)),
    FINANCE("Finance", new Color(0x0F7A6A)),
    POLITICS("Politics", new Color(0x7A3D9B)),
    SOCIETY("Society & Climate", new Color(0xB06A00)),
    TECH("Tech & Science", new Color(0x34495E));

    private final String label;
    private final Color color;

    Category(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }
    public Color color() { return color; }
    public String hex() { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }

    /** Parses a name such as "world", "Finance" or "SOCIETY"; unknown values become WORLD. */
    public static Category parse(String s) {
        if (s == null) return WORLD;
        String k = s.trim().toUpperCase();
        if (k.startsWith("SOC") || k.startsWith("CLIM") || k.startsWith("HEALTH")) return SOCIETY;
        if (k.startsWith("FIN") || k.startsWith("BUS") || k.startsWith("ECON") || k.startsWith("MARK")) return FINANCE;
        if (k.startsWith("POL")) return POLITICS;
        if (k.startsWith("TECH") || k.startsWith("SCI")) return TECH;
        return WORLD;
    }
}
