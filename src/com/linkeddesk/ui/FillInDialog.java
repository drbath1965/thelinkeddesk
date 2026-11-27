package com.linkeddesk.ui;

import com.linkeddesk.feeds.FillIn;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.model.Category;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** News > Fill in past days… (FET-F12): choose days, topics and desks, see the estimate, then start. */
final class FillInDialog extends JDialog {
    private final JSpinner days = new JSpinner(new SpinnerNumberModel(7, 1, FillIn.MAX_DAYS, 1));
    private final Map<Topic, JCheckBox> topics = new LinkedHashMap<>();
    private final Map<Category, JCheckBox> desks = new LinkedHashMap<>();
    private final JCheckBox auto = new JCheckBox("Do this automatically for the days missed when the PC was off for a day or more (up to " + FillIn.AUTO_DAYS + " days)");
    private final JLabel estimate = new JLabel(" ");
    private final JButton start = new JButton("Fill in");
    /** What to fill in, when Start was pressed. */
    List<LocalDate> chosenDays;
    List<Topic> chosenTopics;
    List<Category> chosenDesks;

    FillInDialog(Frame owner, NewsService service) {
        super(owner, "Fill in past days", true);
        service.settings().reload();
        auto.setSelected(service.settings().fillInAuto());
        JPanel tp = new JPanel();
        tp.setLayout(new BoxLayout(tp, BoxLayout.Y_AXIS));
        for (Topic t : service.activeTopics()) { JCheckBox c = new JCheckBox(t.name(), true); c.addActionListener(e -> refresh()); topics.put(t, c); tp.add(c); }
        if (topics.isEmpty()) tp.add(new JLabel("(no topics yet: add some in the Topics view)"));
        JPanel dp = new JPanel();
        dp.setLayout(new BoxLayout(dp, BoxLayout.Y_AXIS));
        for (Category c : Category.values()) { JCheckBox b = new JCheckBox(c.label(), true); b.addActionListener(e -> refresh()); desks.put(c, b); dp.add(b); }
        days.addChangeListener(e -> refresh());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Fill in the last"));
        top.add(days);
        top.add(new JLabel("days (up to " + FillIn.MAX_DAYS + ")"));
        JScrollPane ts = new JScrollPane(tp);
        ts.setBorder(BorderFactory.createTitledBorder("Your topics"));
        ts.setPreferredSize(new Dimension(260, 220));
        JScrollPane ds = new JScrollPane(dp);
        ds.setBorder(BorderFactory.createTitledBorder("Desks"));
        ds.setPreferredSize(new Dimension(220, 220));
        JPanel lists = new JPanel(new GridLayout(1, 2, 8, 0));
        lists.add(ts); lists.add(ds);

        JPanel south = new JPanel(new GridLayout(0, 1, 0, 4));
        estimate.setFont(estimate.getFont().deriveFont(Font.BOLD));
        south.add(estimate);
        south.add(new JLabel("<html><span style='color:gray'>Uses Google News searches limited to one day each. Articles you already have are skipped; "
                + "new ones are marked \"filled in\".</span></html>"));
        south.add(auto);
        JButton cancel = new JButton("Cancel");
        start.addActionListener(e -> {
            chosenDays = FillIn.days(LocalDate.now(), ((Number) days.getValue()).intValue());
            chosenTopics = topics.entrySet().stream().filter(x -> x.getValue().isSelected()).map(Map.Entry::getKey).toList();
            chosenDesks = desks.entrySet().stream().filter(x -> x.getValue().isSelected()).map(Map.Entry::getKey).toList();
            saveAuto(service);
            dispose();
        });
        cancel.addActionListener(e -> { saveAuto(service); dispose(); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(start); buttons.add(cancel);
        south.add(buttons);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(lists, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(start);
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private void saveAuto(NewsService service) {
        if (auto.isSelected() == service.settings().fillInAuto()) return;
        service.settings().reload();
        service.settings().setFillInAuto(auto.isSelected());
        service.settings().save();
    }

    private void refresh() {
        int d = ((Number) days.getValue()).intValue();
        int t = (int) topics.values().stream().filter(AbstractButton::isSelected).count();
        int k = (int) desks.values().stream().filter(AbstractButton::isSelected).count();
        FillIn.Estimate e = FillIn.estimate(d, t, k);
        start.setEnabled(e.searches() > 0);
        estimate.setText(e.searches() == 0 ? "Choose at least one topic or desk."
                : String.format("%,d searches, probably up to %,d new articles, about %s.", e.searches(), e.articles(),
                        e.seconds() < 90 ? e.seconds() + " seconds" : Math.round(e.seconds() / 60.0) + " minutes"));
    }
}
