package com.linkeddesk.ui;

import com.linkeddesk.app.Settings;
import com.linkeddesk.model.Category;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Light or dark colours and the text size, applied to the whole window (Swing look and feel), to the story
 * pages (HTML), and to the list rows. Windows' own display scaling (125-200%) is applied by Java on top.
 */
public final class Theme {
    private Theme() {}

    static boolean dark;
    static float scale = 1f;
    private static final Map<Object, Font> baseFonts = new HashMap<>();
    private static String baseLaf;

    /** Applies the settings' theme and text size to the look and feel; call before creating windows. */
    public static void apply(Settings s) {
        dark = "dark".equalsIgnoreCase(s.theme());
        scale = s.textSize() / 100f;
        try {
            if (dark) {
                MetalLookAndFeel.setCurrentTheme(new Dark());
                UIManager.setLookAndFeel(new MetalLookAndFeel());
            } else {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }
        } catch (Exception ignored) { }
        String laf = UIManager.getLookAndFeel().getClass().getName() + dark;
        if (!laf.equals(baseLaf)) { baseFonts.clear(); baseLaf = laf; }
        UIDefaults d = UIManager.getLookAndFeelDefaults();
        for (Object k : d.keySet().toArray()) {
            Object v = d.get(k);
            if (v instanceof Font f && k.toString().endsWith(".font")) baseFonts.putIfAbsent(k, f);
        }
        for (var e : baseFonts.entrySet()) {
            Font f = e.getValue();
            UIManager.put(e.getKey(), new FontUIResource(f.deriveFont(f.getSize2D() * scale)));
        }
        UIManager.put("Table.rowHeight", Math.round(22 * scale));
        if (dark) {
            UIManager.put("EditorPane.background", new ColorUIResource(c(BG)));
            UIManager.put("ToolTip.background", new ColorUIResource(c("#33373c")));
            UIManager.put("ToolTip.foreground", new ColorUIResource(c(FG_DARK)));
        }
    }

    /** The text sizes zoom steps through (Ctrl + mouse wheel, Ctrl +/-), in percent. */
    static final int[] SIZES = {70, 80, 90, 100, 110, 120, 130, 140, 150, 165, 175, 200, 225, 250, 275, 300};

    /** The next size up (+1) or down (-1) from {@code now}. */
    static int step(int now, int dir) {
        if (dir > 0) { for (int s : SIZES) if (s > now) return s; return SIZES[SIZES.length - 1]; }
        for (int i = SIZES.length - 1; i >= 0; i--) if (SIZES[i] < now) return SIZES[i];
        return SIZES[0];
    }

    /** Re-applies the theme to every open window (after Settings changed it). */
    static void refreshAll(Settings s) {
        apply(s);
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            w.repaint();
        }
    }

    // ------------------------------------------------------------------ colours

    private static final String BG = "#1e2124", FG_DARK = "#e6e3de";

    static String bg() { return dark ? BG : "#ffffff"; }
    static String fg() { return dark ? FG_DARK : "#1b1b1b"; }
    static String muted() { return dark ? "#a3a09a" : "#666666"; }
    static String faint() { return dark ? "#8f8c86" : "#777777"; }
    static String read() { return dark ? "#9a9893" : "#555555"; }
    static String link() { return dark ? "#7fb3f0" : "#1f5fa8"; }
    static String rule() { return dark ? "#3a3e43" : "#cccccc"; }
    static String here() { return dark ? "#3b3420" : "#fff6d6"; }
    static String star() { return dark ? "#e0c040" : "#c9a100"; }
    static String warn() { return dark ? "#f0ab3c" : "#b26a00"; }
    static String ok() { return dark ? "#66bb6a" : "#2e7d32"; }
    static String bad() { return dark ? "#ef7070" : "#c62828"; }
    static String note() { return dark ? "#b8b5af" : "#555555"; }
    static Color lineColor() { return dark ? c("#3a3e43") : new Color(0xDDDDDD); }
    static Color panel() { return dark ? c("#2b2f33") : UIManager.getColor("Panel.background"); }
    static Color canvas() { return c(bg()); }
    static Color text() { return c(fg()); }
    static Color mutedColor() { return c(muted()); }

    /** Desk colours, lighter on the dark background so they stay readable. */
    static String deskHex(Category k) {
        if (!dark) return k.hex();
        return switch (k) {
            case WORLD -> "#6aa6ef"; case FINANCE -> "#4cc7b3"; case POLITICS -> "#c08ee0";
            case SOCIETY -> "#f0ab3c"; case TECH -> "#9fb3c8";
        };
    }
    static Color desk(Category k) { return c(deskHex(k)); }

    static Color c(String hex) { return new Color(Integer.parseInt(hex.substring(1), 16)); }

    /** Text size for HTML pages, in points, scaled by the text-size setting. */
    static String pt(double base) { return String.format(java.util.Locale.ROOT, "%.1fpt", base * scale); }

    /** The dark colours for Swing's cross-platform look. */
    private static final class Dark extends DefaultMetalTheme {
        @Override public String getName() { return "Linked Desk dark"; }
        @Override protected ColorUIResource getPrimary1() { return new ColorUIResource(c("#8ab4f8")); }
        @Override protected ColorUIResource getPrimary2() { return new ColorUIResource(c("#3d6aa3")); }
        @Override protected ColorUIResource getPrimary3() { return new ColorUIResource(c("#2f5583")); }
        @Override protected ColorUIResource getSecondary1() { return new ColorUIResource(c("#6b7076")); }
        @Override protected ColorUIResource getSecondary2() { return new ColorUIResource(c("#45494e")); }
        @Override protected ColorUIResource getSecondary3() { return new ColorUIResource(c("#2b2f33")); }
        @Override protected ColorUIResource getBlack() { return new ColorUIResource(c(FG_DARK)); }
        @Override protected ColorUIResource getWhite() { return new ColorUIResource(c(BG)); }
        @Override public ColorUIResource getHighlightedTextColor() { return new ColorUIResource(Color.WHITE); }
        @Override public ColorUIResource getMenuSelectedForeground() { return new ColorUIResource(Color.WHITE); }
        @Override public ColorUIResource getInactiveControlTextColor() { return new ColorUIResource(c("#8f8c86")); }
        @Override public ColorUIResource getControlDisabled() { return new ColorUIResource(c("#45494e")); }
    }
}
