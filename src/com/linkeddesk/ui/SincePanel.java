package com.linkeddesk.ui;

import com.linkeddesk.analysis.SinceLastVisit;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** "Since you last looked" (UI-F14): what's new since the app was last open, in one list. */
final class SincePanel extends JPanel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private final StoryBrowser browser;
    private final JLabel head = new JLabel(" ");
    final JButton seen = new JButton("Mark all as seen");
    private SinceLastVisit.Result last;

    SincePanel(Host host) {
        super(new BorderLayout());
        browser = new StoryBrowser(host);
        browser.setStories(List.of(), List.of(), null, "Nothing new since you last looked.");
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        top.add(head, BorderLayout.CENTER);
        top.add(seen, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(browser, BorderLayout.CENTER);
    }

    void show(SinceLastVisit.Result r, List<StoryCluster> all) {
        last = r;
        Map<StoryCluster, String> tags = new HashMap<>();
        List<StoryCluster> list = new java.util.ArrayList<>();
        for (var i : r.items()) { tags.put(i.story(), i.tag()); list.add(i.story()); }
        browser.setTags(tags::get, true);
        browser.setStories(list, all, null, "Nothing new since you last looked (" + WHEN.format(Instant.ofEpochMilli(r.since())) + ").");
        head.setText("<html><b>Since you last looked</b> (" + Html.esc(WHEN.format(Instant.ofEpochMilli(r.since()))) + "): " + Html.esc(r.summary()) + "</html>");
    }

    int count() { return last == null ? 0 : last.items().size(); }
    StoryBrowser browser() { return browser; }
    void refreshPage() { browser.refreshDetail(); browser.refreshRows(); }
}
