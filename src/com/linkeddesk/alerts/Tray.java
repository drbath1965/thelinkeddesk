package com.linkeddesk.alerts;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * The app's icon in the Windows notification area (next to the clock), used to show notifications.
 * In the window it stays while the app is open; for the daily task it appears just long enough to show the message.
 */
public final class Tray implements Alerts.Display {
    private TrayIcon icon;
    private final Runnable onClick;

    /** {@code onClick}: what clicking the icon or a notification does (open the window); may be null. */
    public Tray(Runnable onClick) { this.onClick = onClick; }

    public static boolean supported() {
        try { return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported(); } catch (Throwable t) { return false; }
    }

    /** Adds the icon (once). Returns false where there is no notification area. */
    public synchronized boolean install(PopupMenu menu) {
        if (icon != null) return true;
        if (!supported()) return false;
        try {
            icon = new TrayIcon(image(), "The Linked Desk", menu);
            icon.setImageAutoSize(true);
            if (onClick != null) icon.addActionListener(e -> onClick.run());
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (Exception e) {
            icon = null;
            return false;
        }
    }

    public synchronized void remove() {
        if (icon != null) try { SystemTray.getSystemTray().remove(icon); } catch (Exception ignored) { }
        icon = null;
    }

    @Override public synchronized boolean show(String title, String text) {
        if (icon == null && !install(null)) return false;
        icon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        return true;
    }

    /** For the daily task: shows the message, waits for Windows to display it, then takes the icon away. */
    public static Alerts.Display brief() {
        return (title, text) -> {
            Tray t = new Tray(null);
            if (!t.show(title, text)) return false;
            try { Thread.sleep(9000); } catch (InterruptedException ignored) { }
            t.remove();
            return true;
        };
    }

    /** A small "L" in the app's blue. */
    static Image image() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1F5FA8));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        g.drawString("L", 9, 25);
        g.dispose();
        return img;
    }
}
