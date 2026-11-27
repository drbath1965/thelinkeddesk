package com.linkeddesk.ui;

import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.feeds.FeedPacks;
import com.linkeddesk.feeds.FeedPacks.Pack;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.service.NewsService.FeedTest;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Feed packs (FET-F11): see each pack's feeds, test them, add the working ones, or remove a pack in one click. */
final class FeedPacksDialog extends JDialog {
    private final NewsService service;
    private final DefaultListModel<Pack> packs = new DefaultListModel<>();
    private final JList<Pack> list = new JList<>(packs);
    private final JLabel about = new JLabel();
    private final DefaultTableModel feeds = new DefaultTableModel(new Object[]{"Feed", "Desk", "Status", "Listed", "Per day"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel total = new JLabel(" ");
    private final JButton test = new JButton("Test"), add = new JButton("Add pack"), remove = new JButton("Remove pack");
    private final Map<String, List<FeedTest>> tested = new HashMap<>();
    private Set<String> added = Set.of();
    /** Set when packs were added or removed, so the main window can download and redraw. */
    boolean changed;
    /** Feeds just added, to download now. */
    final java.util.List<FeedSource> justAdded = new java.util.ArrayList<>();

    FeedPacksDialog(Frame owner, NewsService service) {
        super(owner, "Feed packs", true);
        this.service = service;
        for (Pack p : FeedPacks.all()) packs.addElement(p);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                Pack p = (Pack) v;
                return super.getListCellRendererComponent(l, (added.contains(p.id()) ? "✓ " : "    ") + p.name(), i, sel, focus);
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showPack(); });
        test.addActionListener(e -> runTest(false));
        add.addActionListener(e -> runTest(true));
        remove.addActionListener(e -> removePack());

        JTable table = new JTable(feeds);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        JPanel right = new JPanel(new BorderLayout(6, 6));
        about.setVerticalAlignment(SwingConstants.TOP);
        right.add(about, BorderLayout.NORTH);
        right.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(total, BorderLayout.NORTH);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        b.add(test); b.add(add); b.add(remove);
        south.add(b, BorderLayout.CENTER);
        right.add(south, BorderLayout.SOUTH);

        JScrollPane left = new JScrollPane(list);
        left.setPreferredSize(new Dimension(220, 360));
        JPanel all = new JPanel(new BorderLayout(10, 0));
        all.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        all.add(left, BorderLayout.WEST);
        all.add(right, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(new JLabel("<html><span style='color:gray'>Packs are added to your feed list (News > Feed health > Edit feeds) as one block. "
                + "Feeds you already have are left out.</span></html>"));
        bottom.add(close);
        JPanel root = new JPanel(new BorderLayout());
        root.add(all, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        reloadAdded();
        list.setSelectedIndex(0);
        setSize(900, 480);
        setLocationRelativeTo(owner);
    }

    private void reloadAdded() {
        try { added = FeedPacks.added(FeedCatalog.rawText()); } catch (Exception e) { added = Set.of(); }
        list.repaint();
    }

    private Pack selected() { return list.getSelectedValue(); }

    private void showPack() {
        Pack p = selected();
        if (p == null) return;
        boolean in = added.contains(p.id());
        List<FeedSource> current = FeedCatalog.load();
        List<FeedSource> missing = FeedPacks.missing(p, current);
        about.setText("<html><div style='width:560px'><b>" + p.name() + "</b>" + (in ? " — added" : "") + "<br>" + p.about() + "</div></html>");
        feeds.setRowCount(0);
        List<FeedTest> t = tested.get(p.id());
        int perDay = 0, working = 0;
        for (FeedSource f : p.feeds()) {
            FeedTest r = t == null ? null : t.stream().filter(x -> x.feed().url().equals(f.url())).findFirst().orElse(null);
            String status = in ? "In your feeds" : !missing.contains(f) ? "You have it already" : r == null ? "Not tested yet" : r.ok() ? "Works" : "Failed: " + r.error();
            if (r != null && r.ok()) { perDay += (int) Math.round(r.perDay()); working++; }
            feeds.addRow(new Object[]{f.name(), f.category().label(), status, r == null ? "" : r.items(), r == null || !r.ok() ? "" : Math.round(r.perDay())});
        }
        total.setText(t == null ? (in ? "This pack is in your feeds." : "Test the pack to see how many articles a day it would add.")
                : String.format("%d of %d feeds work — about %,d articles a day before duplicates.", working, t.size(), perDay));
        test.setEnabled(!in && !missing.isEmpty());
        add.setEnabled(!in && !missing.isEmpty());
        remove.setEnabled(in);
    }

    /** Tests the feeds the pack would add; with {@code thenAdd}, adds the ones that work. */
    private void runTest(boolean thenAdd) {
        Pack p = selected();
        if (p == null) return;
        List<FeedSource> missing = FeedPacks.missing(p, FeedCatalog.load());
        test.setEnabled(false); add.setEnabled(false);
        total.setText("Testing " + missing.size() + " feeds…");
        new SwingWorker<List<FeedTest>, Void>() {
            @Override protected List<FeedTest> doInBackground() { return service.testFeeds(missing, () -> false); }
            @Override protected void done() {
                try {
                    List<FeedTest> r = get();
                    tested.put(p.id(), r);
                    showPack();
                    if (thenAdd) addPack(p, r);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(FeedPacksDialog.this, "The test failed: " + ex.getMessage(), "Feed packs", JOptionPane.ERROR_MESSAGE);
                    showPack();
                }
            }
        }.execute();
    }

    private void addPack(Pack p, List<FeedTest> results) {
        List<FeedSource> ok = results.stream().filter(FeedTest::ok).map(FeedTest::feed).toList();
        if (ok.isEmpty()) {
            JOptionPane.showMessageDialog(this, "None of this pack's feeds answered just now, so nothing was added.\nCheck your connection and try again.", "Feed packs", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String skipped = results.size() > ok.size() ? "\n\n" + (results.size() - ok.size()) + " feed(s) didn't work and were left out." : "";
        try {
            FeedPacks.add(p, ok);
            changed = true;
            justAdded.addAll(ok);
            reloadAdded();
            showPack();
            JOptionPane.showMessageDialog(this, "Added " + ok.size() + " feeds from \"" + p.name() + "\". They'll be downloaded when you close this window." + skipped,
                    "Feed packs", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "The pack wasn't added: " + ex.getMessage(), "Feed packs", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removePack() {
        Pack p = selected();
        if (p == null) return;
        try {
            FeedPacks.remove(p);
            justAdded.removeIf(f -> p.feeds().stream().anyMatch(x -> x.url().equals(f.url())));
            changed = true;
            tested.remove(p.id());
            reloadAdded();
            showPack();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "The pack wasn't removed: " + ex.getMessage(), "Feed packs", JOptionPane.ERROR_MESSAGE);
        }
    }
}
