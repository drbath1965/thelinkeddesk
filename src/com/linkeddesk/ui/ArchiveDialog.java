package com.linkeddesk.ui;

import com.linkeddesk.app.Settings;
import com.linkeddesk.export.Exporter;
import com.linkeddesk.store.ArticleStore;
import com.linkeddesk.store.Backup;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Tools > Archive and backup (STO-F03, STO-F04): the archive's size, the search index, compression, backups. */
final class ArchiveDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault());
    private final Host host;
    private final Settings settings;
    private final JLabel summary = new JLabel("Counting…"), indexInfo = new JLabel(" "), backupInfo = new JLabel(" "), busyLabel = new JLabel(" ");
    private final JSpinner months = new JSpinner(new SpinnerNumberModel(6, 0, 120, 1));
    private final JTextField folder = new JTextField(32);
    private final JCheckBox weekly = new JCheckBox("Back up automatically once a week (after the daily download)");
    private final JSpinner keep = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
    private final JComboBox<Path> monthList = new JComboBox<>();
    private final List<JButton> actions = new ArrayList<>();
    boolean restored;

    ArchiveDialog(Frame owner, Host host) {
        super(owner, "Archive and backup", ModalityType.APPLICATION_MODAL);
        this.host = host;
        this.settings = host.service().settings();
        settings.reload();
        months.setValue(settings.compressAfterMonths());
        folder.setText(settings.backupFolder());
        weekly.setSelected(settings.backupWeekly());
        keep.setValue(settings.backupKeep());
        monthList.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof Path p) setText(ArticleStore.monthOf(p) + (p.getFileName().toString().endsWith(".gz") ? " (compressed)" : ""));
                return this;
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 4, 4); g.gridwidth = 2;

        form.add(section("Your archive"), g); g.gridy++;
        form.add(summary, g); g.gridy++;
        form.add(indexInfo, g); g.gridy++;
        JPanel idx = row(button("Rebuild search index", this::rebuildIndex));
        form.add(idx, g); g.gridy++;
        JPanel exp = row(new JLabel("Export a month for Excel: "), monthList, button("Save as CSV…", this::exportMonth));
        form.add(exp, g); g.gridy++;
        form.add(note("Every article stays in plain monthly files in the data folder. The search index (data\\index) only speeds up Search and Ask; "
                + "it is rebuilt by itself and can be deleted at any time."), g); g.gridy++;

        form.add(section("Older months"), g); g.gridy++;
        form.add(row(new JLabel("Compress months older than "), months, new JLabel(" months (0 = never)  "), button("Compress now", this::compress)), g); g.gridy++;
        form.add(note("A compressed month takes about a fifth of the space and is read and searched as before. Months are compressed after the daily download."), g); g.gridy++;

        form.add(section("Backup"), g); g.gridy++;
        form.add(row(new JLabel("Backup folder: "), folder, button("Choose…", this::chooseFolder)), g); g.gridy++;
        form.add(weekly, g); g.gridy++;
        form.add(row(new JLabel("Keep the newest "), keep, new JLabel(" backups")), g); g.gridy++;
        form.add(row(button("Back up now", this::backup), button("Restore from a backup…", this::restore)), g); g.gridy++;
        form.add(backupInfo, g); g.gridy++;
        form.add(note("A backup is one zip file: every article, your feeds, topics, watchlist, corrections and settings. A folder inside OneDrive "
                + "or on another drive keeps it safe if this PC fails. To move to a new PC, install the app there and restore the latest backup. "
                + "The API key and email password are protected for this Windows account only, so enter them again on a new PC."), g); g.gridy++;
        form.add(busyLabel, g);

        JButton close = new JButton("Close");
        close.addActionListener(e -> { saveSettings(); dispose(); });
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(close);
        JScrollPane sp = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        getContentPane().add(sp, BorderLayout.CENTER);
        getContentPane().add(b, BorderLayout.SOUTH);
        pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(getWidth() + 30, screen.width - 20), Math.min(getHeight(), screen.height - 20));
        setLocationRelativeTo(owner);
        refresh();
    }

    private static JLabel section(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 2f));
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return l;
    }

    private static JLabel note(String s) {
        return new JLabel("<html><div style='width:" + Html.cssPx(520) + "px;color:" + Theme.muted() + "'>" + Html.esc(s) + "</div></html>");
    }

    private static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (Component c : cs) { p.add(c); if (c instanceof JButton) p.add(Box.createHorizontalStrut(8)); }
        return p;
    }

    private JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        actions.add(b);
        return b;
    }

    private void saveSettings() {
        settings.reload();
        settings.setCompressAfterMonths((Integer) months.getValue());
        settings.setBackupFolder(folder.getText());
        settings.setBackupWeekly(weekly.isSelected());
        settings.setBackupKeep((Integer) keep.getValue());
        settings.save();
    }

    /** Runs a job in the background with the buttons off; shows its message, or the error. */
    private void run(String busy, Callable<String> job) {
        for (JButton b : actions) b.setEnabled(false);
        busyLabel.setText(busy);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                for (JButton b : actions) b.setEnabled(true);
                try { String m = get(); busyLabel.setText(" "); if (m != null && !m.isEmpty()) JOptionPane.showMessageDialog(ArchiveDialog.this, m, "Archive and backup", JOptionPane.INFORMATION_MESSAGE); }
                catch (Exception ex) {
                    busyLabel.setText(" ");
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(ArchiveDialog.this, c.getMessage(), "Archive and backup", JOptionPane.ERROR_MESSAGE);
                }
                refresh();
            }
        }.execute();
    }

    private void refresh() {
        new SwingWorker<String[], Void>() {
            @Override protected String[] doInBackground() throws Exception {
                ArticleStore store = host.service().store();
                List<Path> files = store.files();
                long bytes = 0;
                int gz = 0;
                for (Path f : files) { bytes += Files.size(f); if (f.getFileName().toString().endsWith(".gz")) gz++; }
                String a = String.format("%,d articles in %d monthly files%s · %.1f MB on disk%s", store.size(), files.size(),
                        gz > 0 ? " (" + gz + " compressed)" : "", bytes / 1048576.0, files.isEmpty() ? "" : " · since " + ArticleStore.monthOf(files.get(0)));
                long[] st = store.index().stats();
                int stale = store.index().stale().size();
                String i = String.format("Search index: %d of %d months, %.1f MB · %s", st[0] - Math.min(st[0], stale), files.size(), st[1] / 1048576.0,
                        stale == 0 ? "up to date" : stale + (stale == 1 ? " month" : " months") + " still to index (searched the slow way until then)");
                settings.reload();
                String bf = settings.backupFolder();
                String b;
                if (bf.isBlank()) b = "No backup folder chosen yet.";
                else {
                    List<Path> list = Backup.list(Path.of(bf));
                    b = (settings.backupLast() > 0 ? "Last backup " + WHEN.format(Instant.ofEpochMilli(settings.backupLast())) + ". " : "")
                            + list.size() + (list.size() == 1 ? " backup" : " backups") + " in that folder"
                            + (list.isEmpty() ? "." : String.format(", newest %s (%.1f MB).", list.get(0).getFileName(), Files.size(list.get(0)) / 1048576.0));
                }
                return new String[]{a, i, b};
            }
            @Override protected void done() {
                try {
                    String[] r = get();
                    summary.setText(r[0]);
                    indexInfo.setText(r[1]);
                    backupInfo.setText("<html><div style='width:" + Html.cssPx(520) + "px'>" + Html.esc(r[2]) + "</div></html>");
                    Object sel = monthList.getSelectedItem();
                    monthList.removeAllItems();
                    List<Path> fs = host.service().store().files();
                    for (int k = fs.size() - 1; k >= 0; k--) monthList.addItem(fs.get(k));
                    if (sel != null) monthList.setSelectedItem(sel);
                } catch (Exception ex) { summary.setText("Could not read the archive: " + ex.getMessage()); }
            }
        }.execute();
    }

    private void rebuildIndex() {
        run("Rebuilding the search index…", () -> {
            Path dir = host.service().store().index().dir();
            if (Files.isDirectory(dir)) try (var ds = Files.newDirectoryStream(dir, "*.idx")) { for (Path p : ds) Files.deleteIfExists(p); }
            long t0 = System.nanoTime();
            int n = host.service().refreshIndex();
            return String.format("Search index rebuilt: %d months in %.1f s.", n, (System.nanoTime() - t0) / 1e9);
        });
    }

    private void compress() {
        saveSettings();
        int m = (Integer) months.getValue();
        if (m == 0) { JOptionPane.showMessageDialog(this, "Set how many recent months to keep uncompressed (6 is a good choice)."); return; }
        run("Compressing older months…", () -> {
            List<String> done = host.service().compressOldMonths();
            host.service().refreshIndex();
            return done.isEmpty() ? "No month older than " + m + " months is left to compress." : "Compressed " + String.join(", ", done) + ".";
        });
    }

    private void exportMonth() {
        Path month = (Path) monthList.getSelectedItem();
        if (month == null) return;
        JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), "Documents"));
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), "Linked Desk articles " + ArticleStore.monthOf(month) + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        run("Exporting…", () -> {
            int n = Exporter.monthCsv(month, out);
            return String.format("Saved %,d articles to %s.", n, out);
        });
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(folder.getText().isBlank() ? new File(System.getProperty("user.home")) : new File(folder.getText()));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder for backups (e.g. inside OneDrive)");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { folder.setText(fc.getSelectedFile().getAbsolutePath()); saveSettings(); refresh(); }
    }

    private void backup() {
        if (folder.getText().isBlank()) { chooseFolder(); if (folder.getText().isBlank()) return; }
        saveSettings();
        run("Backing up…", () -> {
            long t0 = System.nanoTime();
            Backup.Info i = host.service().backupNow();
            return String.format("Backed up in %.0f s:%n%s", (System.nanoTime() - t0) / 1e9, i.describe());
        });
    }

    private void restore() {
        JFileChooser fc = new JFileChooser(folder.getText().isBlank() ? new File(System.getProperty("user.home")) : new File(folder.getText()));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Linked Desk backups (*.zip)", "zip"));
        fc.setDialogTitle("Restore from a backup");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path zip = fc.getSelectedFile().toPath();
        run("Checking the backup…", () -> {
            Backup.Info info = Backup.read(zip);
            String[] answer = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                int ok = JOptionPane.showConfirmDialog(this, "<html>Restore this backup?<br><br><b>" + Html.esc(info.describe()) + "</b><br><br>"
                        + "Your current archive and settings are replaced by the backup's.<br>Nothing is deleted: they are moved to a folder named "
                        + "before-restore-… in the data folder.</html>", "Restore", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                answer[0] = ok == JOptionPane.OK_OPTION ? "yes" : "no";
            });
            if (!answer[0].equals("yes")) return "";
            SwingUtilities.invokeLater(() -> busyLabel.setText("Restoring…"));
            Path aside = host.service().restore(zip);
            host.service().refreshIndex();
            restored = true;
            return String.format("Restored %,d articles from %s.%s", info.articles(), zip.getFileName(),
                    aside == null ? "" : "\nThe files that were here before are in " + aside);
        });
    }
}
