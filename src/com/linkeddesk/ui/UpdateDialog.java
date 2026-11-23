package com.linkeddesk.ui;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.Settings;
import com.linkeddesk.schedule.WindowsTask;
import com.linkeddesk.service.UpdateCheck;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;

/** Updates from GitHub (OPS-F07): the automatic daily check, Help > Check for updates…, and the offer dialog. */
final class UpdateDialog {
    private UpdateDialog() {}

    /** Checks in the background. {@code manual} always reports; automatic checks stay quiet unless there's something new. */
    static void check(JFrame owner, Settings settings, boolean manual, Runnable beforeExit) {
        settings.reload();
        if (!manual && !UpdateCheck.due(settings.checkUpdates(), settings.updateCheckedAt(), System.currentTimeMillis())) return;
        new SwingWorker<UpdateCheck.Release, Void>() {
            @Override protected UpdateCheck.Release doInBackground() throws Exception { return new UpdateCheck().latest(); }
            @Override protected void done() {
                UpdateCheck.Release r;
                try { r = get(); }
                catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    if (manual) JOptionPane.showMessageDialog(owner, "Couldn't check for updates:\n" + c.getMessage(), "Check for updates", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                settings.reload();
                settings.setUpdateCheckedAt(System.currentTimeMillis());
                settings.save();
                if (r == null || UpdateCheck.compare(r.version(), AppPaths.VERSION) <= 0) {
                    if (manual) JOptionPane.showMessageDialog(owner, "You have the newest version (" + AppPaths.VERSION + ").", "Check for updates", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                if (!manual && r.version().equals(settings.updateSkip())) return;
                offer(owner, settings, r, beforeExit);
            }
        }.execute();
    }

    static void offer(JFrame owner, Settings settings, UpdateCheck.Release r, Runnable beforeExit) {
        Path folder = UpdateCheck.updatableFolder(WindowsTask.jarPath());
        JTextArea notes = new JTextArea(r.notes().isBlank() ? "(No release notes.)" : r.notes(), 14, 60);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setCaretPosition(0);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html><b>The Linked Desk " + Html.esc(r.version()) + " is available.</b> You have " + AppPaths.VERSION + ".<br>"
                + (folder != null && r.hasJar() ? "It downloads next to this copy as LinkedDesk-" + Html.esc(r.version()) + ".jar; this copy stays, and the daily task moves to the new one."
                   : "Download it from the release page.") + "</html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(notes), BorderLayout.CENTER);
        String install = "Download and restart";
        Object[] opts = folder != null && r.hasJar() ? new Object[]{install, "Open release page", "Skip this version", "Later"}
                : new Object[]{"Open release page", "Skip this version", "Later"};
        int c = JOptionPane.showOptionDialog(owner, p, "Update available", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if (c < 0) return;
        String chosen = opts[c].toString();
        if (chosen.equals("Open release page")) {
            try { Desktop.getDesktop().browse(URI.create(r.pageUrl())); } catch (Exception ignored) { }
        } else if (chosen.equals("Skip this version")) {
            settings.reload(); settings.setUpdateSkip(r.version()); settings.save();
        } else if (chosen.equals(install)) {
            download(owner, r, folder, beforeExit);
        }
    }

    private static void download(JFrame owner, UpdateCheck.Release r, Path folder, Runnable beforeExit) {
        JDialog d = new JDialog(owner, "Downloading " + r.version(), Dialog.ModalityType.MODELESS);
        JProgressBar bar = new JProgressBar(0, (int) Math.max(1, r.jarSize()));
        bar.setIndeterminate(r.jarSize() <= 0);
        bar.setStringPainted(r.jarSize() > 0);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(new JLabel("Downloading " + r.jarName() + "…"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.getContentPane().add(p);
        d.setSize(420, 120);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        new SwingWorker<Path, Long>() {
            @Override protected Path doInBackground() throws Exception {
                return new UpdateCheck().download(r, folder, n -> publish(n));
            }
            @Override protected void process(java.util.List<Long> chunks) { bar.setValue((int) (long) chunks.get(chunks.size() - 1)); }
            @Override protected void done() {
                d.dispose();
                try {
                    Path jar = get();
                    UpdateCheck.launch(jar, WindowsTask.javaw());
                    beforeExit.run();
                    System.exit(0);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(owner, "The update wasn't installed:\n" + c.getMessage() + "\n\nThis copy keeps working; you can download it from " + r.pageUrl(),
                            "Update", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }
}
