package com.linkeddesk.ui;

import com.linkeddesk.ai.ClaudeService;
import com.linkeddesk.ai.Explainer;
import com.linkeddesk.export.ExplainerDoc;
import com.linkeddesk.export.Exporter;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The Explainers tab (AI-F08): type a subject (or come from Explain this), see the cost, read the breakdown. */
final class ExplainersPanel extends JPanel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private final Host host;
    private final JTextField subject = new JTextField(28);
    private final JComboBox<Explainer.Level> level = new JComboBox<>(Explainer.Level.values());
    private final JButton go = new JButton("Explain");
    private final JLabel busy = new JLabel(" ");
    private final DefaultListModel<Explainer.Result> model = new DefaultListModel<>();
    private final JList<Explainer.Result> list = new JList<>(model);
    private final JEditorPane page = StoryBrowser.htmlPane();
    private Explainer.Result current;
    private boolean working;

    ExplainersPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        level.setSelectedItem(Explainer.Level.DETAILED);
        level.setToolTipText("Quick: one screen from up to 14 articles. Detailed: the full breakdown from up to 25. Combined article: one news article from up to 30.");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Explain:"));
        top.add(subject);
        top.add(level);
        top.add(go);
        top.add(busy);
        busy.setForeground(Theme.c(Theme.muted()));
        subject.setToolTipText("Any subject in your saved news, e.g. Golden Dome, Strait of Hormuz, the Fed's next move");
        go.addActionListener(e -> start(subject.getText(), null));
        subject.addActionListener(e -> start(subject.getText(), null));

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                super.getListCellRendererComponent(l, v, i, sel, f);
                Explainer.Result r = (Explainer.Result) v;
                boolean art = r.level() == Explainer.Level.ARTICLE;
                setText("<html><div style='width:" + Html.cssPx(230) + "px'><b>" + Html.esc(art ? Explainer.headline(r) : r.subject()) + "</b><br><span style='color:" + (sel ? "" : Theme.muted()) + "'>"
                        + r.level().label + " · " + Html.esc(WHEN.format(Instant.ofEpochMilli(r.created()))) + " · " + r.refs().size() + " articles</span></div></html>");
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()), BorderFactory.createEmptyBorder(5, 8, 5, 6)));
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) show(list.getSelectedValue()); });
        page.addHyperlinkListener(e -> { if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) link(e.getDescription()); });

        JPanel left = new JPanel(new BorderLayout());
        JLabel saved = new JLabel("Saved explainers and articles");
        saved.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        left.add(saved, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(page));
        split.setDividerLocation(300);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        reload(null);
    }

    /** Reloads the saved list, selecting {@code select} (or showing the intro). */
    void reload(Explainer.Result select) {
        model.clear();
        for (Explainer.Result r : host.service().explainers().all()) model.addElement(r);
        if (select != null) {
            for (int i = 0; i < model.size(); i++) if (model.get(i).created() == select.created() && model.get(i).key().equals(select.key())) { list.setSelectedIndex(i); return; }
        }
        if (current == null) intro();
    }

    private void intro() {
        LinkedHashSet<String> ideas = new LinkedHashSet<>();
        try { for (var w : host.service().watches().all()) ideas.add(w.name()); } catch (Exception ignored) { }
        try { for (var t : com.linkeddesk.feeds.TopicCatalog.load()) ideas.add(t.name()); } catch (Exception ignored) { }
        ideas.addAll(List.of("Golden Dome", "Strait of Hormuz", "Hypersonic weapons", "The Federal Reserve"));
        List<String> l = new ArrayList<>(ideas);
        page.setText(Html.explainerIntro(l.subList(0, Math.min(8, l.size())), host.service().claude().hasKey()));
        page.setCaretPosition(0);
    }

    /** Export… for News > Export when this tab is showing; false if no explainer is open. */
    boolean exportOpen() { if (current == null) return false; link("export:"); return true; }

    void refreshPage() { if (current != null) show(current); else intro(); list.repaint(); }

    private void show(Explainer.Result r) {
        current = r;
        subject.setText(r.subject());
        level.setSelectedItem(r.level());
        page.setText(Html.explainer(r, ""));
        page.setCaretPosition(0);
    }

    /** Plans an explainer, shows what it will read and cost, and runs it if you agree. */
    void start(String text, List<StoryCluster> seed) { start(text, seed, (Explainer.Level) level.getSelectedItem()); }

    void start(String text, List<StoryCluster> seed, Explainer.Level lv) {
        if (working) { host.status("An explainer is already being written…"); return; }
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) { JOptionPane.showMessageDialog(this, "Type a subject to explain, for example \"Golden Dome\"."); subject.requestFocusInWindow(); return; }
        if (!host.service().claude().hasKey()) {
            JOptionPane.showMessageDialog(this, "Explainers are written by Claude: add an Anthropic API key in Tools > Settings > Claude.", "Explain", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        subject.setText(s);
        setWorking(true, "Finding the best articles on \"" + s + "\"…");
        NewsService svc = host.service();
        new SwingWorker<NewsService.ExplainPlan, Void>() {
            @Override protected NewsService.ExplainPlan doInBackground() throws Exception { return svc.planExplainer(s, seed, lv); }
            @Override protected void done() {
                NewsService.ExplainPlan plan;
                try { plan = get(); }
                catch (Exception ex) { setWorking(false, " "); error(ex); return; }
                if (!plan.cached() && !confirm(plan)) { setWorking(false, " "); return; }
                setWorking(true, "Claude is reading " + plan.articles() + " articles about \"" + s + "\"…");
                new SwingWorker<Explainer.Result, Void>() {
                    @Override protected Explainer.Result doInBackground() throws Exception { return svc.runExplainer(plan); }
                    @Override protected void done() {
                        setWorking(false, " ");
                        try { Explainer.Result r = get(); current = r; reload(r); show(r); host.status((r.level() == Explainer.Level.ARTICLE ? "Article saved: " + Explainer.headline(r) : "Explainer saved: " + r.subject())); }
                        catch (Exception ex) { error(ex); }
                    }
                }.execute();
            }
        }.execute();
    }

    private boolean confirm(NewsService.ExplainPlan p) {
        DateTimeFormatter d = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());
        String range = d.format(Instant.ofEpochMilli(p.from())) + (d.format(Instant.ofEpochMilli(p.from())).equals(d.format(Instant.ofEpochMilli(p.to()))) ? "" : " to " + d.format(Instant.ofEpochMilli(p.to())));
        String msg = "<html><b>" + (p.level() == Explainer.Level.ARTICLE ? "One article from the linked stories: \"" : Html.esc(p.level().label) + " explainer of \"")
                + Html.esc(p.subject()) + "\"</b><br><br>"
                + "Claude will read " + p.articles() + " articles from " + p.outlets() + " outlets, " + Html.esc(range) + "."
                + (p.previous() != null ? "<br>It also says what changed since your explainer of " + Html.esc(WHEN.format(Instant.ofEpochMilli(p.previous().created())))
                   + " (" + p.newSincePrevious() + " of these articles are newer)." : "")
                + String.format("<br><br>This could cost up to <b>$%.3f</b>. You've used $%.2f of your $%.2f this month.", p.estimate(), p.spent(), p.budget())
                + (p.spent() + p.estimate() > p.budget() ? "<br><span style='color:" + Theme.bad() + "'>That would pass your monthly budget, so it won't be sent.</span>" : "")
                + "</html>";
        return JOptionPane.showConfirmDialog(this, msg, "Explain", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void more(String point) {
        if (current == null || working) return;
        Explainer.Result r = current;
        setWorking(true, point == null ? "Claude is making it simpler…" : "Claude is going deeper on that point…");
        new SwingWorker<Explainer.Result, Void>() {
            @Override protected Explainer.Result doInBackground() throws Exception { return host.service().explainMore(r, point); }
            @Override protected void done() {
                setWorking(false, " ");
                try {
                    Explainer.Result out = get();
                    current = out;
                    reload(out);
                    show(out);
                    page.setCaretPosition(page.getDocument().getLength());
                } catch (Exception ex) { error(ex); }
            }
        }.execute();
    }

    private void link(String href) {
        if (href == null) return;
        if (href.startsWith("http")) { try { Desktop.getDesktop().browse(URI.create(href)); } catch (Exception ignored) { } return; }
        if (href.startsWith("try:")) { start(href.substring(4), null); return; }
        if (current == null) return;
        switch (href) {
            case "simpler:" -> more(null);
            case "update:" -> start(current.subject(), null, current.level());
            case "detailed:" -> start(current.subject(), null, current.level() == Explainer.Level.QUICK ? Explainer.Level.DETAILED : Explainer.Level.QUICK);
            case "copy:" -> { ExportUi.copy(ExplainerDoc.build(current)); host.status("Explainer copied with its links."); }
            case "export:" -> ExportUi.save(this, host, ExplainerDoc.build(current), List.of(), Exporter.Format.WORD, Exporter.Format.HTML, Exporter.Format.MARKDOWN);
            case "delete:" -> {
                if (JOptionPane.showConfirmDialog(this, "Delete the explainer \"" + current.subject() + "\"?", "Delete", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                try { host.service().explainers().delete(current); current = null; reload(null); }
                catch (Exception ex) { error(ex); }
            }
            default -> {
                if (href.startsWith("deeper:")) {
                    String[] p = href.split(":");
                    if (p.length == 3) {
                        var lines = current.sections().get(p[1].replace('_', ' '));
                        int i = Integer.parseInt(p[2]);
                        if (lines != null && i < lines.size()) more(lines.get(i));
                    }
                }
            }
        }
    }

    private void setWorking(boolean on, String text) {
        working = on;
        go.setEnabled(!on);
        busy.setText(text);
        setCursor(on ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    private void error(Exception ex) {
        Throwable c = ex.getCause() != null && ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
        int type = c instanceof ClaudeService.BudgetException || c instanceof IllegalStateException || c instanceof IllegalArgumentException
                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
        JOptionPane.showMessageDialog(this, c.getMessage() == null ? c.toString() : c.getMessage(), "Explain", type);
    }
}
