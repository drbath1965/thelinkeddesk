package com.linkeddesk.ui;

import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.DownloadVolume;
import com.linkeddesk.feeds.DownloadVolume.Level;
import com.linkeddesk.schedule.WindowsTask;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** How much news is downloaded (FET-F10): presets, limits, the estimate, and what was actually downloaded. */
final class DownloadsDialog extends JDialog {
    private final NewsService service;
    private final JComboBox<Level> level = new JComboBox<>(Level.values());
    private final JLabel about = new JLabel();
    private final JComboBox<String> every = new JComboBox<>(new String[]{"Once a day", "Every hour", "Every 2 hours", "Every 4 hours", "Every 6 hours", "Every 12 hours"});
    private static final int[] HOURS = {0, 1, 2, 4, 6, 12};
    private final JSpinner perFeed = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JSpinner perDay = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 100));
    private final JComboBox<String> depth = new JComboBox<>(new String[]{"One Google News edition", "+ UK edition", "+ UK, India, Australia, Canada"});
    private final JLabel estimate = new JLabel();
    private boolean loading;

    DownloadsDialog(Frame owner, NewsService service) {
        super(owner, "How much news is downloaded", true);
        this.service = service;
        Settings s = service.settings();
        s.reload();
        loading = true;
        level.setSelectedItem(Level.parse(s.downloadVolume()));
        show(DownloadVolume.of(s));
        loading = false;
        level.addActionListener(e -> { if (!loading) { loading = true; show(DownloadVolume.of((Level) level.getSelectedItem(), hours(), num(perFeed), num(perDay), depth.getSelectedIndex() + 1)); loading = false; refresh(); } });
        for (JComponent c : new JComponent[]{every, depth}) ((JComboBox<?>) c).addActionListener(e -> custom());
        perFeed.addChangeListener(e -> custom());
        perDay.addChangeListener(e -> custom());
        for (JSpinner sp : new JSpinner[]{perFeed, perDay}) ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setColumns(7);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 4, 4);
        row(form, g, "Amount:", level);
        g.gridwidth = 2; form.add(about, g); g.gridy++; g.gridwidth = 1;
        row(form, g, "Download:", every);
        row(form, g, "At most per feed each time (0 = no limit):", perFeed);
        row(form, g, "At most per day (0 = no limit):", perDay);
        row(form, g, "Search your topics in:", depth);
        g.gridwidth = 2;
        estimate.setFont(estimate.getFont().deriveFont(Font.BOLD));
        form.add(estimate, g); g.gridy++;
        form.add(new JLabel("<html><div style='width:430px;color:gray'>Feeds only list their newest 10–50 articles, so busy feeds lose articles "
                + "between once-a-day downloads; downloading more often catches them. With a daily limit, your topics and the outlets you "
                + "weight highest (Sources) keep their articles first. Every few hours also runs from the Windows task when the app is "
                + "closed (it is updated when you save, if you've set it up).</div></html>"), g);

        JTabbedPane history = new JTabbedPane();
        history.addTab("Per day (30 days)", new JScrollPane(perDayTable()));
        history.addTab("Per feed (7 days)", new JScrollPane(perFeedTable()));
        history.setPreferredSize(new Dimension(520, 220));
        history.setBorder(BorderFactory.createTitledBorder("What was downloaded"));

        JButton ok = new JButton("Save"), cancel = new JButton("Cancel");
        ok.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok); buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        JPanel all = new JPanel(new BorderLayout());
        all.add(form, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout());
        mid.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        mid.add(history);
        all.add(mid, BorderLayout.CENTER);
        all.add(buttons, BorderLayout.SOUTH);
        setContentPane(all);
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private static void row(JPanel p, GridBagConstraints g, String label, JComponent c) {
        g.gridx = 0; p.add(new JLabel(label), g); g.gridx = 1; p.add(c, g); g.gridx = 0; g.gridy++;
    }

    private void show(DownloadVolume.Plan p) {
        int i = 0;
        for (int k = 0; k < HOURS.length; k++) if (HOURS[k] == p.everyHours()) i = k;
        every.setSelectedIndex(i);
        perFeed.setValue(p.maxPerFeed());
        perDay.setValue(p.maxPerDay());
        depth.setSelectedIndex(Math.max(0, Math.min(2, p.topicDepth() - 1)));
    }

    /** Changing any detail makes it Custom. */
    private void custom() {
        if (loading) return;
        loading = true;
        if (!plan().equals(DownloadVolume.of((Level) level.getSelectedItem(), 0, 0, 0, 1)) ) level.setSelectedItem(Level.CUSTOM);
        loading = false;
        refresh();
    }

    private int hours() { return HOURS[Math.max(0, every.getSelectedIndex())]; }
    private static int num(JSpinner s) { return ((Number) s.getValue()).intValue(); }

    DownloadVolume.Plan plan() {
        return new DownloadVolume.Plan((Level) level.getSelectedItem(), hours(), num(perFeed), num(perDay), depth.getSelectedIndex() + 1);
    }

    private void refresh() {
        Level l = (Level) level.getSelectedItem();
        about.setText("<html><div style='width:430px'>" + l.about + "</div></html>");
        DownloadVolume.Plan p = plan();
        DownloadVolume.Plan now = DownloadVolume.of(service.settings());
        var e = service.volumeEstimate(p);
        var cur = service.volumeEstimate(now);
        String change = p.equals(now) || cur.perDay() == 0 ? "" : String.format(" (now about %,d)", cur.perDay());
        estimate.setText(String.format("About %,d new articles a day%s, %s, about %s of disk a year.", e.perDay(), change, p.schedule(), e.size()));
    }

    private JTable perDayTable() {
        DefaultTableModel m = new DefaultTableModel(new Object[]{"Day", "New articles"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE d MMM");
        var days = service.downloadsPerDay(30).descendingMap();
        for (var d : days.entrySet()) m.addRow(new Object[]{f.format(d.getKey()), d.getValue()});
        JTable t = new JTable(m);
        t.setName("perDay");
        return t;
    }

    private JTable perFeedTable() {
        DefaultTableModel m = new DefaultTableModel(new Object[]{"Feed", "New articles"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        for (Map.Entry<String, Integer> e : service.downloadsPerFeed(7)) m.addRow(new Object[]{e.getKey(), e.getValue()});
        JTable t = new JTable(m);
        t.getColumnModel().getColumn(0).setPreferredWidth(380);
        return t;
    }

    private void save() {
        Settings s = service.settings();
        DownloadVolume.Plan p = plan();
        s.reload();
        int before = DownloadVolume.of(s).everyHours();
        s.setDownloadVolume(p.level().name());
        s.setDownloadEveryHours(p.everyHours());
        s.setMaxPerFeed(p.maxPerFeed());
        s.setMaxPerDay(p.maxPerDay());
        s.setTopicDepth(p.topicDepth());
        s.save();
        if (p.everyHours() != before && WindowsTask.supported() && Boolean.TRUE.equals(safeRunsThisCopy())) {
            try { WindowsTask.install(s.dailyTime(), p.everyHours()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Saved, but the Windows task wasn't updated:\n" + ex.getMessage()
                    + "\n\nUse Tools > Settings > \"Run daily even when the app is closed\" to set it up again.", "Downloads", JOptionPane.WARNING_MESSAGE); }
        }
        dispose();
    }

    private static Boolean safeRunsThisCopy() {
        try { return WindowsTask.runsThisCopy(); } catch (RuntimeException e) { return null; }
    }
}
