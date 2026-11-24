package com.linkeddesk.ui;

import com.linkeddesk.alerts.Watches;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Watchlist tab (ALR-F01): your watched subjects with how many stories each has, and what's new since you last
 * looked. Opening a watch shows its stories from the last 30 days, the new ones first and marked.
 */
final class WatchPanel extends JPanel {
    static final int DAYS = 30;
    private final Host host;
    private final DefaultListModel<Watches.Watch> model = new DefaultListModel<>();
    private final JList<Watches.Watch> list = new JList<>(model);
    private final StoryBrowser browser;
    StoryBrowser browser() { return browser; }
    private final JLabel info = new JLabel(" ");
    private final Map<String, int[]> counts = new HashMap<>();   // name -> {new, stories}
    private final JButton edit = new JButton("Edit…"), delete = new JButton("Delete");
    private int run;

    WatchPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        browser = new StoryBrowser(host);
        browser.setStories(List.of(), List.of(), null, "Add a subject to watch (a person, place, company or issue).");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                Watches.Watch w = (Watches.Watch) v;
                int[] c = counts.get(w.name());
                String sub = c == null ? "counting…" : (c[0] > 0 ? "<b style='color:" + (s ? "#ffffff" : Theme.link()) + "'>" + c[0] + " new</b> · " : "")
                        + c[1] + (c[1] == 1 ? " story" : " stories") + " in " + DAYS + " days" + (w.alert() ? " · notifies" : "");
                setText("<html><b>" + Html.esc(w.name()) + "</b><br><span style='color:" + (s ? "#ffffff" : Theme.muted()) + "'>" + sub
                        + "</span><br><span style='color:" + (s ? "#ffffff" : Theme.faint()) + "'>" + Html.esc(w.words()) + "</span></html>");
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()), BorderFactory.createEmptyBorder(6, 10, 6, 6)));
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) open(list.getSelectedValue()); });
        JButton add = new JButton("Add…");
        add.addActionListener(e -> addWatch(""));
        edit.addActionListener(e -> editSelected());
        delete.addActionListener(e -> deleteSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton explain = new JButton("Explain");
        explain.setToolTipText("Claude reads this watch's stories and explains the subject");
        explain.addActionListener(e -> { var w = list.getSelectedValue(); if (w != null) host.explainSubject(w.name(), browser.shown()); });
        buttons.add(add); buttons.add(edit); buttons.add(delete); buttons.add(explain);
        JPanel left = new JPanel(new BorderLayout());
        JLabel intro = new JLabel("<html><div style='width:" + Html.cssPx(280) + "px;color:" + Theme.note() + "'>Subjects you follow across all desks. "
                + "New matches are highlighted in Stories after each download and, for watches that notify, pop up as a Windows notification.</div></html>");
        intro.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        left.add(intro, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout());
        info.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        right.add(info, BorderLayout.NORTH);
        right.add(browser, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(330);
        add(split, BorderLayout.CENTER);
    }

    /** Re-reads the watchlist and recounts (after a download or a change). */
    void reload() {
        var ws = host.service().watches();
        Watches.Watch sel = list.getSelectedValue();
        model.clear();
        model.addAll(ws.all());
        edit.setEnabled(!model.isEmpty());
        delete.setEnabled(!model.isEmpty());
        if (model.isEmpty()) browser.setStories(List.of(), List.of(), null, "Add a subject to watch (a person, place, company or issue).");
        for (int i = 0; i < model.size(); i++) if (sel != null && model.get(i).name().equals(sel.name())) list.setSelectedIndex(i);
        int my = ++run;
        new SwingWorker<Map<String, int[]>, Void>() {
            @Override protected Map<String, int[]> doInBackground() throws Exception {
                Map<String, int[]> m = new HashMap<>();
                for (Watches.Watch w : ws.all()) {
                    Analyzer.Result r = host.service().watchStories(w, DAYS);
                    long seen = ws.lastSeen(w.name());
                    int fresh = 0;
                    for (StoryCluster c : r.stories()) if (newSince(c, seen)) fresh++;
                    m.put(w.name(), new int[]{fresh, r.stories().size()});
                }
                return m;
            }
            @Override protected void done() {
                if (my != run) return;
                try { counts.clear(); counts.putAll(get()); list.repaint(); host.watchesChanged(); } catch (Exception ignored) { }
            }
        }.execute();
    }

    static boolean newSince(StoryCluster c, long seen) {
        for (Article a : c.articles) if (a.fetched > seen) return true;
        return false;
    }

    int newTotal() { int n = 0; for (int[] c : counts.values()) n += c[0]; return n; }

    private void open(Watches.Watch w) {
        if (w == null) return;
        var ws = host.service().watches();
        long seen = ws.lastSeen(w.name());
        info.setText("Stories about \"" + w.name() + "\" in the last " + DAYS + " days…");
        new SwingWorker<Analyzer.Result, Void>() {
            @Override protected Analyzer.Result doInBackground() throws Exception { return host.service().watchStories(w, DAYS); }
            @Override protected void done() {
                try {
                    Analyzer.Result r = get();
                    List<StoryCluster> fresh = new ArrayList<>(), old = new ArrayList<>();
                    for (StoryCluster c : r.stories()) (newSince(c, seen) ? fresh : old).add(c);
                    List<StoryCluster> shown = new ArrayList<>(fresh);
                    shown.addAll(old);
                    browser.setTags(c -> fresh.contains(c) ? "new since you last looked" : null, true);
                    browser.setStories(shown, r.stories(), null, "Nothing about \"" + w.name() + "\" in the last " + DAYS + " days yet.");
                    info.setText("<html><b>" + fresh.size() + " new</b> since you last looked" + (seen == 0 ? "" : " (" + Html.esc(Html.ago(seen)) + ")")
                            + " · " + r.stories().size() + " stories in " + DAYS + " days · search words: " + Html.esc(w.words()) + "</html>");
                    ws.markSeen(w.name(), System.currentTimeMillis());
                    counts.computeIfPresent(w.name(), (k, v) -> new int[]{0, v[1]});
                    list.repaint();
                    host.watchesChanged();
                } catch (Exception ex) { info.setText("Could not show the watch: " + ex.getMessage()); }
            }
        }.execute();
    }

    /** Opens the editor for a new watch, with these words filled in. */
    void addWatch(String words) {
        WatchEditor ed = new WatchEditor(SwingUtilities.getWindowAncestor(this), null, words, host.service().watches().all());
        ed.setVisible(true);
        if (ed.result() == null) return;
        List<Watches.Watch> all = new ArrayList<>(host.service().watches().all());
        all.add(ed.result());
        save(all, ed.result().name());
    }

    private void editSelected() {
        Watches.Watch w = list.getSelectedValue();
        if (w == null) return;
        List<Watches.Watch> others = new ArrayList<>(host.service().watches().all());
        others.removeIf(x -> x.name().equals(w.name()));
        WatchEditor ed = new WatchEditor(SwingUtilities.getWindowAncestor(this), w, w.words(), others);
        ed.setVisible(true);
        if (ed.result() == null) return;
        List<Watches.Watch> all = new ArrayList<>(host.service().watches().all());
        all.replaceAll(x -> x.name().equals(w.name()) ? ed.result() : x);
        save(all, ed.result().name());
    }

    private void deleteSelected() {
        Watches.Watch w = list.getSelectedValue();
        if (w == null) return;
        if (JOptionPane.showConfirmDialog(this, "Stop watching \"" + w.name() + "\"? (The stories stay in your archive.)", "Delete watch",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        List<Watches.Watch> all = new ArrayList<>(host.service().watches().all());
        all.removeIf(x -> x.name().equals(w.name()));
        save(all, null);
    }

    private void save(List<Watches.Watch> all, String select) {
        try {
            host.service().watches().save(all);
            reload();
            if (select != null) for (int i = 0; i < model.size(); i++) if (model.get(i).name().equals(select)) list.setSelectedIndex(i);
            host.watchesChanged();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the watchlist: " + ex.getMessage());
        }
    }

    void refreshPage() { browser.refreshDetail(); list.repaint(); }
}
