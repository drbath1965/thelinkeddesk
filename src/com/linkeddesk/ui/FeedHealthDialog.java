package com.linkeddesk.ui;

import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Feed health: every feed (and every topic's search feeds) with its state, last success, failures in a
 * row, new articles per day and last error. Retry, fix the address, remove, or test every feed now.
 */
final class FeedHealthDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private static final String[] COLS = {"Feed", "Desk", "Status", "Last success", "Fails in a row", "New per day", "Last error"};

    private final Host host;
    private final List<FeedSource> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");

    FeedHealthDialog(Window owner, Host host) {
        super(owner, "Feed health", ModalityType.MODELESS);
        this.host = host;
        table.setRowHeight(22);
        table.setAutoCreateRowSorter(false);
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        sorter.setComparator(4, Comparator.comparingInt(o -> (Integer) o));
        sorter.setComparator(5, Comparator.comparingDouble(o -> (Double) o));
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(6).setPreferredWidth(320);
        table.setDefaultRenderer(Object.class, new StatusRenderer());
        table.setDefaultRenderer(Integer.class, new StatusRenderer());
        table.setDefaultRenderer(Double.class, new StatusRenderer());

        JButton retry = new JButton("Retry now"), change = new JButton("Change address…"), remove = new JButton("Remove…"),
                test = new JButton("Test all feeds"), close = new JButton("Close");
        retry.setToolTipText("Un-pause the selected feeds so the next download tries them again");
        test.setToolTipText("Download every feed now, including paused ones, and show what works");
        retry.addActionListener(e -> retry());
        change.addActionListener(e -> changeAddress());
        remove.addActionListener(e -> removeSelected());
        test.addActionListener(e -> testAll());
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : List.of(retry, change, remove, test)) buttons.add(b);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(close);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.WEST);
        south.add(right, BorderLayout.EAST);
        summary.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JLabel note = new JLabel("<html><span style='color:" + Theme.muted() + "'>A feed that fails " + FeedHealth.PAUSE_AFTER
                + " times in a row is paused and tried again a week later. One success clears it.</span></html>");
        note.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        JPanel north = new JPanel(new BorderLayout());
        north.add(summary, BorderLayout.NORTH);
        north.add(note, BorderLayout.SOUTH);

        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(1180, 640);
        setLocationRelativeTo(owner);
        reload();
    }

    void reload() {
        rows.clear();
        rows.addAll(FeedCatalog.load());
        for (Topic t : TopicCatalog.load()) rows.addAll(t.feeds());
        model.fireTableDataChanged();
        long now = System.currentTimeMillis();
        int ok = 0, failing = 0, paused = 0, fresh = 0;
        for (FeedSource f : rows) {
            FeedHealth.Entry e = host.service().health().find(f.url());
            switch (e == null ? FeedHealth.State.NEW : e.state(now)) {
                case OK, NOT_MODIFIED -> ok++;
                case FAILING -> failing++;
                case PAUSED -> paused++;
                case NEW -> fresh++;
            }
        }
        summary.setText(String.format("<html><b>%d feeds</b> · %d working · %d failing · %d paused · %d not checked yet</html>",
                rows.size(), ok, failing, paused, fresh));
    }

    private List<FeedSource> selected() {
        List<FeedSource> out = new ArrayList<>();
        for (int v : table.getSelectedRows()) out.add(rows.get(table.convertRowIndexToModel(v)));
        return out;
    }

    private void retry() {
        List<FeedSource> sel = selected();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(this, "Select one or more feeds first."); return; }
        for (FeedSource f : sel) host.service().health().resume(f.url());
        saveHealth();
        reload();
        host.status(sel.size() + " feed(s) will be tried on the next download.");
    }

    private void changeAddress() {
        List<FeedSource> sel = selected();
        if (sel.size() != 1) { JOptionPane.showMessageDialog(this, "Select one feed to change its address."); return; }
        FeedSource f = sel.get(0);
        if (f.isTopic()) { JOptionPane.showMessageDialog(this, "This is a search feed for the topic \"" + f.topic() + "\". Change the topic's search words on the Topics tab."); return; }
        String nu = (String) JOptionPane.showInputDialog(this, "New feed address for " + f.name() + ":", "Change address",
                JOptionPane.PLAIN_MESSAGE, null, null, f.url());
        if (nu == null || nu.isBlank() || nu.trim().equals(f.url())) return;
        if (!nu.trim().matches("(?i)https?://\\S+")) { JOptionPane.showMessageDialog(this, "That doesn't look like a web address."); return; }
        try {
            FeedCatalog.changeUrl(f.url(), nu.trim());
            host.service().health().forget(f.url());
            saveHealth();
            reload();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save feeds.txt: " + ex.getMessage()); }
    }

    private void removeSelected() {
        List<FeedSource> sel = selected().stream().filter(f -> !f.isTopic()).toList();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(this, "Select regular feeds to remove (topic feeds are removed by deleting the topic)."); return; }
        int ok = JOptionPane.showConfirmDialog(this, "Remove " + sel.size() + " feed(s) from your feed list?\nArticles already saved are kept.",
                "Remove feeds", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            for (FeedSource f : sel) { FeedCatalog.removeFeed(f.url()); host.service().health().forget(f.url()); }
            saveHealth();
            reload();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save feeds.txt: " + ex.getMessage()); }
    }

    private void testAll() {
        host.runDownload("Testing every feed…", (progress, cancelled) -> host.service().testAllFeeds(progress, cancelled), r -> {
            reload();
            host.articlesChanged();
            long total = r.results.size(), failed = r.failures();
            String pct = total == 0 ? "–" : String.format("%.0f%%", 100.0 * (total - failed) / total);
            JOptionPane.showMessageDialog(this, String.format("%d of %d feeds working (%s).%s", total - failed, total, pct,
                    failed > 0 ? "\nFailing feeds are shown in red; fix or remove them here." : ""), "Test all feeds", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void saveHealth() {
        try { host.service().health().save(); } catch (Exception ex) { host.status("Could not save feed health: " + ex.getMessage()); }
    }

    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 4 ? Integer.class : c == 5 ? Double.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            FeedSource f = rows.get(r);
            FeedHealth.Entry e = host.service().health().find(f.url());
            long now = System.currentTimeMillis();
            return switch (c) {
                case 0 -> f.isTopic() ? f.name() : f.name();
                case 1 -> f.isTopic() ? f.category().label() + " (topic)" : f.category().label();
                case 2 -> status(e, now);
                case 3 -> e == null || e.lastSuccess == 0 ? "never" : WHEN.format(Instant.ofEpochMilli(e.lastSuccess));
                case 4 -> e == null ? 0 : e.failures;
                case 5 -> e == null ? 0.0 : Math.round(e.perDay(LocalDate.now()) * 10) / 10.0;
                default -> e == null || e.failures == 0 ? "" : e.lastError;
            };
        }
    }

    static String status(FeedHealth.Entry e, long now) {
        if (e == null) return "Not checked yet";
        return switch (e.state(now)) {
            case OK, NOT_MODIFIED -> "Working";
            case FAILING -> "Failing";
            case PAUSED -> "Paused until " + DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.pausedUntil));
            case NEW -> "Not checked yet";
        };
    }

    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            String st = String.valueOf(t.getValueAt(row, 2));
            if (!sel) setForeground(st.startsWith("Failing") || st.startsWith("Paused") ? new Color(0xB42318)
                    : st.startsWith("Working") ? new Color(0x1F2937) : new Color(0x6B7280));
            setHorizontalAlignment(v instanceof Number ? RIGHT : LEFT);
            return this;
        }
    }
}
