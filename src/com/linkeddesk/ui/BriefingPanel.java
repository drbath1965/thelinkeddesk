package com.linkeddesk.ui;

import com.linkeddesk.analysis.Briefing;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/** The Briefing tab: the daily page on the left, the story you click on the right. */
final class BriefingPanel extends JPanel {
    private final Host host;
    private final JEditorPane page = StoryPane.htmlPane();
    private final StoryPane story;
    private final Map<String, StoryCluster> byId = new HashMap<>();
    private final JLabel status = new JLabel(" ");
    private Briefing briefing;
    private com.linkeddesk.ai.Digest digest;
    private int run;

    BriefingPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        story = new StoryPane(host);
        story.lookup = byId::get;
        story.openStory = id -> { StoryCluster c = byId.get(id); if (c != null) story.show(c, true); };
        story.stateChanged = this::rerender;
        story.setEmptyMessage("Click a story in the briefing to read it here.");
        page.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String href = e.getDescription();
            if (href.startsWith("story:")) {
                StoryCluster c = byId.get(href.substring(6));
                if (c != null) story.show(c, true);
            } else if (href.equals("digest:") || href.equals("digest-again:")) {
                claude("Claude is writing today's digest…", () -> { digest = host.service().writeDigest(briefing, href.equals("digest-again:")); return "Digest written."; });
            } else if (href.equals("check:")) {
                claude("Claude is checking today's top links…", () -> {
                    int n = host.service().checkLinks(briefing, Math.max(1, host.service().settings().claudeCheckLinks()));
                    return n == 0 ? "No new links to check: today's top links were already checked." : n + " links checked; verdicts are shown on the links.";
                });
            }
        });
        JScrollPane left = new JScrollPane(page);
        left.getVerticalScrollBar().setUnitIncrement(16);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, story);
        split.setDividerLocation(620);
        split.setResizeWeight(0.5);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        top.add(status, BorderLayout.CENTER);
        top.add(refresh, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        page.setText(Html.empty("Preparing your briefing…"));
    }

    Briefing briefing() { return briefing; }

    /** Rebuilds the briefing from the saved news (in the background). */
    void reload() {
        int r = ++run;
        status.setText("Preparing your briefing…");
        new SwingWorker<Briefing, Void>() {
            @Override protected Briefing doInBackground() throws Exception {
                Briefing b = host.service().briefing();
                digest = host.service().todaysDigest();
                return b;
            }
            @Override protected void done() {
                if (r != run) return;
                try {
                    briefing = get();
                    byId.clear();
                    for (StoryCluster c : briefing.result().stories()) byId.put(c.id, c);
                    rerender();
                    status.setText("Bold = not read yet. The briefing covers the 24 hours before " + Html.ago(briefing.now()) + ".");
                } catch (Exception ex) {
                    status.setText("Could not prepare the briefing: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void rerender() {
        if (briefing == null) return;
        Point p = ((JViewport) page.getParent()).getViewPosition();
        var svc = host.service();
        java.util.function.Function<String, StoryCluster> ref = id -> {
            if (digest == null || !digest.refs().containsKey(id)) return null;
            return com.linkeddesk.ai.Cite.find(digest.refs().get(id), briefing.result().stories());
        };
        String digestHtml = Html.digest(digest, ref, svc.settings().hasApiKey(), svc.claude().summary());
        page.setText(Html.briefing(briefing, svc.reads(), svc.regions(), digestHtml));
        SwingUtilities.invokeLater(() -> ((JViewport) page.getParent()).setViewPosition(p));
    }

    void refreshPage() { rerender(); story.refresh(); }

    private interface Job { String run() throws Exception; }

    /** Runs a Claude request in the background and shows the result (or why it wasn't sent). */
    private void claude(String busy, Job job) {
        status.setText(busy);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return job.run(); }
            @Override protected void done() {
                try {
                    status.setText(get() + " " + host.service().claude().summary());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    status.setText("Claude: " + c.getMessage());
                    JOptionPane.showMessageDialog(BriefingPanel.this, c.getMessage(), "Claude", JOptionPane.INFORMATION_MESSAGE);
                }
                rerender();
                story.refresh();
            }
        }.execute();
    }
}
