package com.linkeddesk.ui;

import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Category;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Search tab: searches every saved article (all history), and can fetch fresh news for the same words
 * (Search live news) or turn the search into a daily topic (Save as topic).
 */
final class SearchPanel extends JPanel {
    private record When(String label, long days) { @Override public String toString() { return label; } }

    private final Host host;
    private final JComboBox<String> query = new JComboBox<>();
    private final JComboBox<Object> desk = new JComboBox<>();
    private final JComboBox<When> when = new JComboBox<>(new When[]{
            new When("Any time", 0), new When("Past 24 hours", 1), new When("Past week", 7),
            new When("Past month", 31), new When("Past year", 366)});
    private final JTextField source = new JTextField(12);
    private final JButton searchBtn = new JButton("Search");
    private final JButton liveBtn = new JButton("Search live news");
    private final JButton saveBtn = new JButton("Save as topic…");
    private final JLabel result = new JLabel(" ");
    private final StoryBrowser browser;
    StoryBrowser browser() { return browser; }

    SearchPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        browser = new StoryBrowser(host);
        query.setEditable(true);
        query.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        reloadRecent();
        desk.addItem("Any desk");
        for (Category c : Category.values()) desk.addItem(c);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Search for:"));
        top.add(query);
        top.add(searchBtn);
        top.add(liveBtn);
        top.add(saveBtn);
        JButton explainBtn = Html.claudeOnly(new JButton("Explain these results"));
        explainBtn.setToolTipText("Claude reads the best of these articles and explains the subject");
        explainBtn.addActionListener(e -> {
            String q = text();
            if (q.isEmpty() || browser.shown().isEmpty()) { JOptionPane.showMessageDialog(this, "Search for something first."); return; }
            host.explainSubject(q.replaceAll("\\b(source|desk|after|before|region|is):\\S+", "").replace("\"", "").replaceAll("\\s+", " ").strip(), browser.shown());
        });
        top.add(explainBtn);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filters.add(new JLabel("Desk:")); filters.add(desk);
        filters.add(new JLabel("When:")); filters.add(when);
        filters.add(new JLabel("Source contains:")); filters.add(source);
        JLabel help = new JLabel("<html><span style='color:" + Theme.muted() + "'>Tips: <b>\"strait of hormuz\"</b> exact phrase · "
                + "<b>nvidia OR anthropic</b> either · <b>hegseth -caracas</b> leave out · filters: <b>source:bbc</b> <b>desk:finance</b> "
                + "<b>after:2026-11-01</b> <b>before:…</b> <b>after:7d</b> <b>region:europe</b> <b>is:unread</b> <b>is:starred</b> · searches every article ever saved.</span></html>");
        help.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        result.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : List.of(top, filters, help, result)) { c.setAlignmentX(LEFT_ALIGNMENT); north.add(c); }
        add(north, BorderLayout.NORTH);
        add(browser, BorderLayout.CENTER);

        searchBtn.addActionListener(e -> search());
        query.getEditor().addActionListener(e -> search());
        liveBtn.addActionListener(e -> live());
        saveBtn.addActionListener(e -> saveAsTopic());
        browser.setStories(List.of(), List.of(), null, "Type words above and press Search.");
    }

    void focusQuery() { query.getEditor().getEditorComponent().requestFocusInWindow(); }

    private String text() { return String.valueOf(query.getEditor().getItem()).trim(); }

    private NewsService.SearchFilter filter() {
        long days = ((When) when.getSelectedItem()).days();
        long from = days == 0 ? 0 : System.currentTimeMillis() - days * 24L * 3600_000;
        Category d = desk.getSelectedItem() instanceof Category c ? c : null;
        return new NewsService.SearchFilter(from, 0, d, source.getText().trim());
    }

    private void remember(String q) {
        host.service().settings().reload();
        host.service().settings().addRecentSearch(q);
        host.service().settings().save();
        reloadRecent();
        query.getEditor().setItem(q);
    }

    private void reloadRecent() {
        Object cur = query.getEditor().getItem();
        query.removeAllItems();
        for (String r : host.service().settings().recentSearches()) query.addItem(r);
        query.getEditor().setItem(cur == null ? "" : cur);
    }

    void search() {
        String q = text();
        if (q.isEmpty()) return;
        SearchQuery sq = new SearchQuery(q);
        NewsService.SearchFilter f = filter();
        remember(q);
        result.setText("Searching all saved news…");
        searchBtn.setEnabled(false);
        new SwingWorker<NewsService.SearchResult, Void>() {
            @Override protected NewsService.SearchResult doInBackground() throws Exception { return host.service().search(sq, f); }
            @Override protected void done() {
                searchBtn.setEnabled(true);
                try { show(sq, get(), false); }
                catch (Exception ex) { result.setText("Search failed: " + ex.getMessage()); }
            }
        }.execute();
    }

    private void live() {
        String q = text();
        if (q.isEmpty()) { JOptionPane.showMessageDialog(this, "Type the words to search for first."); return; }
        SearchQuery sq = new SearchQuery(q);
        NewsService.SearchFilter f = filter();
        remember(q);
        result.setText("Searching live news for \"" + q + "\"…");
        host.runDownload("Searching live news…", (progress, cancelled) -> host.service().liveSearch(sq, f, progress, cancelled), r -> {
            show(sq, r, true);
            host.articlesChanged();
        });
    }

    private SearchQuery lastQuery;
    private NewsService.SearchFilter lastFilter;

    /** Re-runs the last archive search (after a correction), keeping what's on screen until it's done. */
    void refresh() {
        if (lastQuery == null) return;
        SearchQuery sq = lastQuery;
        NewsService.SearchFilter f = lastFilter;
        new SwingWorker<NewsService.SearchResult, Void>() {
            @Override protected NewsService.SearchResult doInBackground() throws Exception { return host.service().search(sq, f); }
            @Override protected void done() {
                try { NewsService.SearchResult r = get(); if (r.total() > 0) show(sq, r, false); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Searching", ex); }
            }
        }.execute();
    }

    private void show(SearchQuery sq, NewsService.SearchResult r, boolean live) {
        lastQuery = sq;
        lastFilter = filter();
        browser.setHighlight(sq);
        browser.setStories(r.stories().stories(), r.stories().stories(), null,
                live ? "No news found for these words." : "No saved articles match. Try Search live news to fetch fresh articles.");
        String where = ((When) when.getSelectedItem()).days() == 0 ? "all history" : ((When) when.getSelectedItem()).label().toLowerCase();
        String problems = sq.problems().isEmpty() ? "" : " · <span style='color:" + Theme.warn() + "'>Ignored: " + Html.esc(String.join("; ", sq.problems())) + "</span>";
        result.setText(String.format("<html>%s<b>%,d</b> article%s in <b>%,d</b> stor%s (%s, %.1f s)%s" + problems.replace("%", "%%") + "</html>",
                live ? "Live search saved new articles. " : "",
                r.total(), r.total() == 1 ? "" : "s", r.stories().stories().size(), r.stories().stories().size() == 1 ? "y" : "ies",
                where, r.millis() / 1000.0,
                r.capped() ? " · showing the newest " + String.format("%,d", NewsService.SEARCH_CAP) + "; narrow the search to see older ones" : ""));
    }

    private void saveAsTopic() {
        String q = text();
        if (q.isEmpty()) { JOptionPane.showMessageDialog(this, "Type the words to search for first."); return; }
        Category d = desk.getSelectedItem() instanceof Category c ? c : Category.WORLD;
        q = new SearchQuery(q).wordsText();
        if (q.isEmpty()) { JOptionPane.showMessageDialog(this, "A topic needs some words to look for; filters like source: only apply to saved news."); return; }
        String name = q.replace("\"", "").replaceAll("\\s+-\\S+", "").replaceAll("\\s+OR\\s+", " / ").trim();
        if (name.length() > 40) name = name.substring(0, 40).trim();
        Topic draft = new Topic(d, Character.toUpperCase(name.charAt(0)) + name.substring(1), q, "en-US", false);
        TopicEditor ed = new TopicEditor(SwingUtilities.getWindowAncestor(this), draft, TopicCatalog.load(), true);
        ed.setVisible(true);
        Topic t = ed.result();
        if (t == null) return;
        try {
            List<Topic> all = new java.util.ArrayList<>(TopicCatalog.load());
            all.add(t);
            TopicCatalog.save(all);
            host.status("Topic \"" + t.name() + "\" saved. It downloads with every daily run.");
            if (topicsChanged != null) topicsChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the topic: " + ex.getMessage());
        }
    }

    Runnable topicsChanged;
}
