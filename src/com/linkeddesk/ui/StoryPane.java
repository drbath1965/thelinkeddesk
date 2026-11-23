package com.linkeddesk.ui;

import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One story's page: headline, summary, coverage spread, storyline, linked stories and coverage, with the
 * actions on it (star, mark unread, Right/Wrong, Not this story, Move to another desk, Ask Claude).
 * Used by the story lists, the briefing, the connection map and the world map.
 */
final class StoryPane extends JScrollPane {
    private final JEditorPane detail = htmlPane();
    private final Host host;
    private SearchQuery highlight;
    private String emptyMessage = "Nothing to show.";
    private StoryCluster current;
    private boolean userPick;
    /** Opens a linked story (by id); the owner decides how (select it in a list, or just show it here). */
    Consumer<String> openStory = id -> { };
    /** Finds a story by id for links that point outside the owner's list. */
    Function<String, StoryCluster> lookup = id -> null;
    /** Called after the read or starred state changed, so the owner can repaint its list. */
    Runnable stateChanged = () -> { };
    /** A story counts as read once it has been open for a moment after you picked it. */
    private final Timer readTimer = new Timer(1200, e -> markReadNow());

    StoryPane(Host host) {
        this.host = host;
        setViewportView(detail);
        getVerticalScrollBar().setUnitIncrement(16);
        readTimer.setRepeats(false);
        detail.addHyperlinkListener(this::onLink);
        detail.setText(Html.empty(emptyMessage));
    }

    void setHighlight(SearchQuery q) { this.highlight = q; }
    void setEmptyMessage(String m) { this.emptyMessage = m; if (current == null) detail.setText(Html.empty(m)); }
    StoryCluster current() { return current; }

    /** Shows a story; {@code picked} = you chose it (so it will count as read). */
    void show(StoryCluster c, boolean picked) {
        userPick = picked;
        show(c, true, picked);
    }

    void refresh() { if (current != null) show(current, false, false); }

    private void show(StoryCluster c, boolean fresh, boolean startTimer) {
        readTimer.stop();
        if (c == null) { detail.setText(Html.empty(emptyMessage)); current = null; return; }
        var reads = host.service().reads();
        int pos = fresh ? 0 : detail.getCaretPosition();
        detail.setText(Html.story(c, true, highlight, true, new Html.Extras(reads.isStarred(c), host.service().regions().spread(c), reads.hasNewSinceRead(c),
                (a, b) -> host.service().linkChecks().find(a, b), host.service().store()::hasFullText)));
        detail.setCaretPosition(Math.min(pos, detail.getDocument().getLength()));
        current = c;
        if (fresh && startTimer) readTimer.restart();
    }

    private void markReadNow() {
        StoryCluster c = current;
        if (c == null || !userPick) return;
        try {
            if (host.service().reads().isUnread(c)) {
                host.service().reads().markRead(c);
                stateChanged.run();
                show(c, false, false);
            }
        } catch (Exception ex) { host.status("Could not save read state: " + ex.getMessage()); }
    }

    private void onLink(HyperlinkEvent e) {
        if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
        String href = e.getDescription();
        if (href.startsWith("story:")) {
            openStory.accept(href.substring(6));
        } else if (href.startsWith("explain:") && current != null) {
            link(href.substring(8)).ifPresent(l -> host.explain(current, l));
        } else if ((href.startsWith("good:") || href.startsWith("bad:")) && current != null) {
            boolean right = href.startsWith("good:");
            link(href.substring(href.indexOf(':') + 1)).ifPresent(l -> correct(() -> host.service().judgeLink(current, l.to(), right, l.sharedTerms()),
                    right ? "Marked right: this link will always be shown." : "Marked wrong: the link is hidden, and links resting on the same words count less."));
        } else if (href.startsWith("undo:") && current != null) {
            link(href.substring(5)).ifPresent(l -> {
                com.linkeddesk.analysis.Feedback.Entry v = host.service().options().feedback().find(current, l.to());
                if (v != null) correct(() -> host.service().removeVerdict(v), "Your verdict on that link was removed.");
            });
        } else if (href.startsWith("split:") && current != null) {
            String id = href.substring(6);
            current.articles.stream().filter(a -> a.id.equals(id)).findFirst().ifPresent(a -> {
                int ok = JOptionPane.showConfirmDialog(this, "<html>Take this article out of the story?<br><br><b>" + Html.esc(a.title)
                        + "</b><br><br>It will be kept apart from this story's other articles from now on<br>(and the app learns from it when calibrating).</html>",
                        "Not this story", JOptionPane.OK_CANCEL_OPTION);
                if (ok == JOptionPane.OK_OPTION) correct(() -> host.service().splitArticle(a, current), "Article taken out of the story.");
            });
        } else if ((href.equals("star:") || href.equals("unstar:") || href.equals("unread:")) && current != null) {
            StoryCluster c = current;
            try {
                var reads = host.service().reads();
                if (href.equals("star:")) { reads.star(c); host.status("Starred. Find it any time in the Saved tab."); }
                else if (href.equals("unstar:")) { reads.unstar(c); host.status("Removed from Saved."); }
                else { reads.markUnread(c); readTimer.stop(); host.status("Marked unread."); }
                stateChanged.run();
                show(c, false);
                host.savedChanged();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
            }
        } else if (href.startsWith("mute:")) {
            String outlet = java.net.URLDecoder.decode(href.substring(5), java.nio.charset.StandardCharsets.UTF_8);
            if (JOptionPane.showConfirmDialog(this, "<html>Mute <b>" + Html.esc(outlet) + "</b>?<br><br>Its articles disappear from your lists, the briefing and search,<br>"
                    + "but stay in the archive. Undo any time in Tools &gt; Sources.</html>", "Mute outlet", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                try {
                    host.service().sources().set(outlet, true, 1);
                    host.status("Muted " + outlet + ". Tools > Sources lists your muted outlets.");
                    host.articlesChanged();
                } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage()); }
            }
        } else if (href.startsWith("full:") && current != null) {
            String id = href.substring(5);
            current.articles.stream().filter(a -> a.id.equals(id)).findFirst().ifPresent(this::showFullText);
        } else if (href.equals("copy:") && current != null) {
            host.copyStory(current);
        } else if (href.equals("export:") && current != null) {
            host.exportStory(current);
        } else if (href.equals("watch:") && current != null) {
            host.addWatch(watchWords(current));
        } else if (href.equals("desk:") && current != null) {
            DeskDialog d = new DeskDialog(SwingUtilities.getWindowAncestor(this), current);
            d.setVisible(true);
            if (d.rule() != null) correct(() -> host.service().addDeskRule(d.rule()), "Desk rule saved: " + d.rule().describe() + ".");
        } else {
            try { Desktop.getDesktop().browse(URI.create(href)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not open the link: " + ex.getMessage()); }
        }
    }

    /** The full text the publisher's feed gave (FET-F07), in a reading window. */
    private void showFullText(com.linkeddesk.model.Article a) {
        String text = host.service().fullText(a);
        if (text.isEmpty()) { host.status("No full text was saved for this article."); return; }
        JEditorPane p = StoryBrowser.htmlPane();
        StringBuilder sb = new StringBuilder("<html><head><style>" + Html.css() + " p{margin:0 0 10px 0}</style></head><body><h1>" + Html.esc(a.title) + "</h1><p class='meta'>"
                + Html.esc(a.source) + " · full text from the publisher's own feed · <a href='" + Html.esc(a.url) + "'>open the article</a></p>");
        for (String para : text.split("\n\n")) sb.append("<p>").append(Html.esc(para)).append("</p>");
        p.setText(sb.append("</body></html>").toString());
        p.setCaretPosition(0);
        p.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED)
                try { Desktop.getDesktop().browse(URI.create(e.getDescription())); } catch (Exception ignored) { }
        });
        JScrollPane sp = new JScrollPane(p);
        sp.setPreferredSize(new Dimension(Html.cssPx(700) + 60, 620));
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), a.source + ": " + a.title, Dialog.ModalityType.MODELESS);
        d.getContentPane().add(sp);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    /** Words to start a watch with: the story's leading key term (quoted if it's a phrase). */
    static String watchWords(StoryCluster c) {
        if (c.keyTerms.isEmpty()) return "";
        String t = c.keyTerms.get(0);
        return t.contains(" ") ? "\"" + t + "\"" : t;
    }

    private java.util.Optional<StoryLink> link(String toId) {
        return current.links.stream().filter(l -> l.to().id.equals(toId)).findFirst();
    }

    private interface Action { void run() throws Exception; }

    /** Saves a correction, then regroups every view so it shows at once. */
    private void correct(Action a, String done) {
        try {
            a.run();
            host.status(done);
            host.correctionsChanged();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "The Linked Desk", JOptionPane.ERROR_MESSAGE);
        }
    }

    static JEditorPane htmlPane() {
        JEditorPane p = new JEditorPane("text/html", "");
        p.setEditable(false);
        p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return p;
    }
}
