package com.linkeddesk.ui;

import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A list of stories with the story's page beside it. Used by the Stories, Saved, Topics and Search tabs.
 * Clicking a linked story opens it in the same browser; article links open in your web browser.
 */
final class StoryBrowser extends JSplitPane {
    private final DefaultListModel<StoryCluster> model = new DefaultListModel<>();
    private final JList<StoryCluster> list = new JList<>(model);
    private final StoryPane pane;
    private final Map<String, StoryCluster> byId = new HashMap<>();
    private final Host host;
    private boolean userPick;

    StoryBrowser(Host host) {
        super(JSplitPane.HORIZONTAL_SPLIT);
        this.host = host;
        pane = new StoryPane(host);
        pane.openStory = this::select;
        pane.lookup = byId::get;
        pane.stateChanged = list::repaint;
        list.setCellRenderer(new StoryRenderer(host, this));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { userPick = true; }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) { userPick = true; }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) pane.show(list.getSelectedValue(), userPick); });
        JScrollPane l = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        l.getVerticalScrollBar().setUnitIncrement(16);
        setLeftComponent(l);
        setRightComponent(pane);
        setDividerLocation(520);
        setResizeWeight(0.4);
    }

    /** Shows these stories; {@code all} is every story the links may point to (for navigation). */
    void setStories(List<StoryCluster> shown, List<StoryCluster> all, String keepSelectedId, String emptyMessage) {
        pane.setEmptyMessage(emptyMessage);
        byId.clear();
        for (StoryCluster c : all) byId.put(c.id, c);
        model.clear();
        model.addAll(shown);
        int idx = -1;
        if (keepSelectedId != null) for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(keepSelectedId)) { idx = i; break; }
        if (idx < 0 && !shown.isEmpty()) idx = 0;
        userPick = false;
        if (idx >= 0) { list.setSelectedIndex(idx); list.ensureIndexIsVisible(idx); pane.show(shown.get(idx), false); }
        else pane.show(null, false);
    }

    void setHighlight(SearchQuery q) { pane.setHighlight(q); }

    private java.util.function.Function<StoryCluster, String> tags;
    private boolean tagHighlight;

    /**
     * Puts a small label above some stories (null for none): "new" in a watch, the day in Markets. With highlight the
     * row is also tinted. Without a tag function, stories new on your watchlist are tagged.
     */
    /** Re-measures every row (when what a row shows changed, e.g. watch marks arrived). */
    void refreshRows() { list.setFixedCellHeight(10); list.setFixedCellHeight(-1); list.revalidate(); list.repaint(); }

    void setTags(java.util.function.Function<StoryCluster, String> tags, boolean highlight) { this.tags = tags; tagHighlight = highlight; refreshRows(); }

    StoryCluster selected() { return list.getSelectedValue(); }
    int storyCount() { return model.size(); }
    boolean contains(String id) { for (int i = 0; i < model.size(); i++) if (model.get(i).id.equals(id)) return true; return false; }
    StoryCluster lookup(String id) { return byId.get(id); }
    void focusList() { list.requestFocusInWindow(); }

    /** Moves the selection down (+1) or up (-1): J and K. */
    void step(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        userPick = true;
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    void select(String id) {
        userPick = true;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).id.equals(id)) {
                if (list.getSelectedIndex() == i) pane.show(model.get(i), true);
                list.setSelectedIndex(i); list.ensureIndexIsVisible(i); return;
            }
        }
        StoryCluster c = byId.get(id);
        if (c != null) { list.clearSelection(); pane.show(c, true); }
    }

    void refreshDetail() { pane.refresh(); list.repaint(); }

    static JEditorPane htmlPane() { return StoryPane.htmlPane(); }
    /** List row: desk colour bar, headline, desk · sources · age · links. */
    static final class StoryRenderer extends DefaultListCellRenderer {
        private final Host host;
        private final StoryBrowser owner;
        StoryRenderer(Host host, StoryBrowser owner) { this.host = host; this.owner = owner; }

        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(list, value, index, sel, focus);
            StoryCluster c = (StoryCluster) value;
            int w = Math.max(200, list.getParent() != null ? list.getParent().getWidth() - 40 : list.getWidth() - 40);
            long cross = c.links.stream().filter(StoryLink::crossDesk).count();
            String links = c.links.isEmpty() ? "" : " · ↔ " + c.links.size() + (c.links.size() == 1 ? " link" : " links")
                    + (cross > 0 ? " (" + cross + " other desk)" : "");
            String meta0 = c.category.label() + (c.also != null ? " + " + c.also.label() : "") + " · " + c.sourceCount() + (c.sourceCount() == 1 ? " source" : " sources")
                    + " · " + Html.ago(c.lastSeen) + links;
            var reads = host.service().reads();
            var sp = host.service().regions().spread(c);
            String meta = meta0 + (c.continues != null || !c.continuedBy.isEmpty() ? " · storyline" : "")
                    + (sp.singleSource() ? " · single source" : sp.regionCount() >= 2 ? " · " + sp.regionCount() + " regions" : "");
            java.util.List<String> watching = host.watchesFor(c);
            if (!watching.isEmpty()) meta += " · watching: " + String.join(", ", watching);
            boolean unread = reads.isUnread(c);
            String tag = owner.tags != null ? owner.tags.apply(c) : host.newForWatch(c) ? "new on your watchlist" : null;
            boolean tint = tag != null && (owner.tags == null || owner.tagHighlight);
            String star = reads.isStarred(c) ? "<span style='color:" + (sel ? "#ffffff" : Theme.star()) + "'>★</span> " : "";
            String dot = unread ? "<span style='color:" + (sel ? "#ffffff" : Theme.link()) + "'>●</span> " : "";
            String head = unread ? dot + "<b>" + Html.esc(c.headline) + "</b>" : "<span style='color:" + (sel ? "#ffffff" : Theme.read()) + "'>" + Html.esc(c.headline) + "</span>";
            if (unread && reads.hasNewSinceRead(c)) head += " <span style='color:" + (sel ? "#ffffff" : Theme.link()) + "'>· new</span>";
            setText("<html><div style='width:" + Html.cssPx(w) + "px'>" + star + head + "<br>"
                    + "<span style='color:" + (sel ? "#ffffff" : Theme.muted()) + "'>" + Html.esc(meta) + "</span></div></html>");
            if (tag != null) {
                String bg = tint ? Theme.warn() : Theme.muted();
                setText(getText().replace("<div style=", "<div><span style='color:#ffffff;background:" + bg + "'>&nbsp;" + Html.esc(tag) + "&nbsp;</span><br><div style=") + "</div>");
                if (tint && !sel) setBackground(Theme.c(Theme.here()));
            }
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 6, 1, 0, Theme.desk(c.category)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 6)));
            return this;
        }
    }

    static Category[] desks() { return Category.values(); }
}
