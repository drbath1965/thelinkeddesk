package com.linkeddesk.ui;

import com.linkeddesk.ai.ArchiveQA;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * The Ask tab (AI-F04): ask a question about anything in your saved news; the app finds the matching stories
 * and Claude answers from them, citing each one. Click a citation to read the story beside the answer.
 */
final class AskPanel extends JPanel {
    private final Host host;
    private final JComboBox<String> question = new JComboBox<>();
    private final JButton ask = new JButton("Ask Claude");
    private final JLabel status = new JLabel(" ");
    private final JEditorPane answer = StoryPane.htmlPane();
    private final StoryPane story;
    private final Map<String, StoryCluster> byId = new HashMap<>();
    ArchiveQA.Answer last;

    AskPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        story = new StoryPane(host);
        story.lookup = byId::get;
        story.openStory = id -> { StoryCluster c = byId.get(id); if (c != null) story.show(c, true); };
        story.setEmptyMessage("Click a citation like [S2] to read that story here.");
        question.setEditable(true);
        question.setPrototypeDisplayValue("x".repeat(70));
        for (String ex : new String[]{"What happened with the Strait of Hormuz this month?", "How are AI stocks and the Nasdaq connected this week?",
                "What is behind Belgium's strike?", "What did the Xi-Trump-Putin summit lead to?"}) question.addItem(ex);
        question.getEditor().setItem("");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Ask about your news:"));
        top.add(question);
        top.add(ask);
        JLabel help = new JLabel("<html><span style='color:" + Theme.muted() + "'>Claude answers only from stories saved in this app and cites them "
                + "([S1], [S2]…). Words like <b>this week</b> or <b>this month</b> narrow the search. Each new question costs about one or two US cents; "
                + "the same question again is free (cached).</span></html>");
        help.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : java.util.List.of(top, help, status)) { c.setAlignmentX(LEFT_ALIGNMENT); north.add(c); }
        answer.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || !e.getDescription().startsWith("story:")) return;
            StoryCluster c = byId.get(e.getDescription().substring(6));
            if (c != null) story.show(c, true);
        });
        JScrollPane left = new JScrollPane(answer);
        left.getVerticalScrollBar().setUnitIncrement(16);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, story);
        split.setResizeWeight(0.5);
        split.setDividerLocation(640);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        ask.addActionListener(e -> ask());
        question.getEditor().addActionListener(e -> ask());
        answer.setText(Html.empty("Type a question above, for example: What happened with the Strait of Hormuz this month?"));
    }

    void focusQuestion() { question.getEditor().getEditorComponent().requestFocusInWindow(); }

    void ask() {
        String q = String.valueOf(question.getEditor().getItem()).trim();
        if (q.isEmpty()) return;
        if (!host.service().settings().hasApiKey()) {
            JOptionPane.showMessageDialog(this, "Add an Anthropic API key in Tools > Settings to ask Claude.", "Ask", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ask.setEnabled(false);
        status.setText("Finding the stories in your archive and asking Claude…");
        new SwingWorker<ArchiveQA.Answer, Void>() {
            @Override protected ArchiveQA.Answer doInBackground() throws Exception { return host.service().askArchive(q); }
            @Override protected void done() {
                ask.setEnabled(true);
                try {
                    show(get());
                    status.setText(host.service().claude().summary());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    status.setText("Claude: " + c.getMessage());
                    answer.setText(Html.empty(c.getMessage()));
                }
            }
        }.execute();
    }

    void show(ArchiveQA.Answer a) {
        last = a;
        byId.clear();
        for (StoryCluster c : a.refs().values()) byId.put(c.id, c);
        answer.setText(Html.answer(a, host.service().claude().summary()));
        answer.setCaretPosition(0);
        if (!a.cited().isEmpty()) story.show(a.refs().get(a.cited().get(0)), false);
    }

    void refreshPage() { if (last != null) show(last); story.refresh(); }
}
