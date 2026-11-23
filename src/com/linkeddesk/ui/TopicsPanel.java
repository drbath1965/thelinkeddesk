package com.linkeddesk.ui;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.feeds.FeedHealth;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Topics tab: your custom topics on the left, the stories the selected topic found on the right. */
final class TopicsPanel extends JPanel {
    private final Host host;
    private final DefaultListModel<Topic> model = new DefaultListModel<>();
    private final JList<Topic> list = new JList<>(model);
    private final StoryBrowser browser;
    private final JButton edit = new JButton("Edit…"), pause = new JButton("Pause"), delete = new JButton("Delete…"), now = new JButton("Download now");

    TopicsPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        browser = new StoryBrowser(host);
        list.setCellRenderer(new TopicRenderer());
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });

        JButton add = new JButton("Add topic…");
        add.addActionListener(e -> addTopic());
        edit.addActionListener(e -> editTopic());
        pause.addActionListener(e -> togglePause());
        delete.addActionListener(e -> deleteTopic());
        now.addActionListener(e -> downloadSelected());
        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        for (JButton b : List.of(add, edit, pause, delete, now)) buttons.add(b);

        JLabel intro = new JLabel("<html><div style='width:280px;color:" + Theme.note() + "'>Topics are searched across world news on every "
                + "daily download, so you get stories the regular feeds don't carry. Their stories also join the main "
                + "Stories tab and link to related news.</div></html>");
        intro.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JPanel left = new JPanel(new BorderLayout());
        left.add(intro, BorderLayout.NORTH);
        left.add(new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(360, 400));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, browser);
        split.setDividerLocation(370);
        add(split, BorderLayout.CENTER);
        reload(null);
    }

    void reload(String keep) {
        String sel = keep != null ? keep : list.getSelectedValue() == null ? null : list.getSelectedValue().name();
        model.clear();
        List<Topic> topics = TopicCatalog.load();
        model.addAll(topics);
        int idx = 0;
        for (int i = 0; i < topics.size(); i++) if (topics.get(i).name().equals(sel)) idx = i;
        if (!topics.isEmpty()) list.setSelectedIndex(idx);
        else browser.setStories(List.of(), List.of(), null, "No topics yet. Click \"Add topic…\" to follow anything you like.");
        updateButtons();
    }

    private void updateButtons() {
        Topic t = list.getSelectedValue();
        for (JButton b : List.of(edit, pause, delete, now)) b.setEnabled(t != null);
        pause.setText(t != null && t.paused() ? "Resume" : "Pause");
    }

    /** Regroups the selected topic's stories (after a correction). */
    void refresh() { if (list.getSelectedValue() != null) showSelected(); }

    private void showSelected() {
        updateButtons();
        Topic t = list.getSelectedValue();
        if (t == null) return;
        new SwingWorker<Analyzer.Result, Void>() {
            @Override protected Analyzer.Result doInBackground() { return host.service().analyzeTopic(t.name(), 0); }
            @Override protected void done() {
                try {
                    Analyzer.Result r = get();
                    browser.setStories(r.stories(), r.stories(), null,
                            "Nothing found for \"" + t.name() + "\" yet. Click \"Download now\" or wait for the next daily download.");
                } catch (Exception ex) { host.status("Could not show the topic: " + ex.getMessage()); }
            }
        }.execute();
    }

    private void save(List<Topic> topics, String keep) {
        try { TopicCatalog.save(topics); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save topics.txt: " + ex.getMessage()); }
        reload(keep);
    }

    void addTopic() {
        List<Topic> all = new ArrayList<>(TopicCatalog.load());
        TopicEditor ed = new TopicEditor(SwingUtilities.getWindowAncestor(this), null, all, true);
        ed.setVisible(true);
        Topic t = ed.result();
        if (t == null) return;
        all.add(t);
        save(all, t.name());
        if (!t.paused()) download(t);
    }

    private void editTopic() {
        Topic cur = list.getSelectedValue();
        if (cur == null) return;
        List<Topic> all = new ArrayList<>(TopicCatalog.load());
        TopicEditor ed = new TopicEditor(SwingUtilities.getWindowAncestor(this), cur, all, false);
        ed.setVisible(true);
        Topic t = ed.result();
        if (t == null) return;
        all.replaceAll(x -> x.name().equals(cur.name()) ? t : x);
        save(all, t.name());
    }

    private void togglePause() {
        Topic cur = list.getSelectedValue();
        if (cur == null) return;
        List<Topic> all = new ArrayList<>(TopicCatalog.load());
        all.replaceAll(x -> x.name().equals(cur.name()) ? new Topic(x.desk(), x.name(), x.query(), x.region(), !x.paused()) : x);
        save(all, cur.name());
    }

    private void deleteTopic() {
        Topic cur = list.getSelectedValue();
        if (cur == null) return;
        int ok = JOptionPane.showConfirmDialog(this, "Delete the topic \"" + cur.name() + "\"?\nArticles it already found stay saved and searchable.",
                "Delete topic", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        List<Topic> all = new ArrayList<>(TopicCatalog.load());
        all.removeIf(x -> x.name().equals(cur.name()));
        save(all, null);
    }

    private void downloadSelected() {
        Topic t = list.getSelectedValue();
        if (t != null) download(t);
    }

    private void download(Topic t) {
        host.runDownload("Downloading news for \"" + t.name() + "\"…",
                (progress, cancelled) -> host.service().fetchTopic(t, progress, cancelled),
                r -> {
                    host.status("\"" + t.name() + "\": " + r.summary());
                    reload(t.name());
                    host.articlesChanged();
                });
    }

    /** Topic row: name, desk and search words, counts, and any problem. */
    private final class TopicRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, value, index, sel, focus);
            Topic t = (Topic) value;
            long now = System.currentTimeMillis();
            int today = host.service().topicHits().countSince(t.name(), now - 24L * 3600_000);
            int week = host.service().topicHits().countSince(t.name(), now - 7L * 24 * 3600_000);
            String problem = "";
            int failing = 0;
            String err = "";
            for (FeedSource f : t.feeds()) {
                FeedHealth.Entry e = host.service().health().find(f.url());
                if (e != null && e.failures > 0) { failing++; err = e.lastError; }
            }
            if (failing == t.feeds().size()) problem = "<br><span style='color:" + (sel ? "#ffdddd" : Theme.bad()) + "'>Not working: " + Html.esc(err) + "</span>";
            String grey = sel ? "#ffffff" : Theme.muted();
            setText("<html><div style='width:270px'><b>" + Html.esc(t.name()) + "</b>" + (t.paused() ? " <i>(paused)</i>" : "")
                    + "<br><span style='color:" + grey + "'>" + Html.esc(t.desk().label()) + " · " + Html.esc(t.query()) + "</span>"
                    + "<br><span style='color:" + grey + "'>" + today + " new today · " + week + " this week</span>" + problem + "</div></html>");
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 6, 1, 0, t.desk().color()),
                    BorderFactory.createEmptyBorder(6, 8, 6, 6)));
            return this;
        }
    }
}
