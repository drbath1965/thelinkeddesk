package com.linkeddesk.ui;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.StoryThread;
import com.linkeddesk.model.Storyline;
import com.linkeddesk.schedule.DailyScheduler;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The main window: Briefing, Stories, Saved, Storylines, Threads, Map, World, Topics and Search tabs, with downloads
 * that can be cancelled and keyboard shortcuts (J/K, /, S, U, F, B, ?).
 */
public final class MainWindow extends JFrame implements Host {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(ZoneId.systemDefault());

    private record TimeWindow(String label, int days) { @Override public String toString() { return label; } }
    private enum Sort {
        COVERED("Most covered"), NEWEST("Newest"), LINKED("Most linked");
        final String label; Sort(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final NewsService service;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private Analyzer.Result result = new Analyzer.Result(List.of(), List.of(), 0);
    private FetchReport lastReport;
    private int analysisRun;

    private final JComboBox<TimeWindow> window = new JComboBox<>(new TimeWindow[]{
            new TimeWindow("Last 24 hours", 1), new TimeWindow("Last 3 days", 3), new TimeWindow("Last 7 days", 7),
            new TimeWindow("Last 30 days", 30), new TimeWindow("All history", 0)});
    private final JComboBox<Object> desk = new JComboBox<>();
    private final JComboBox<Sort> sort = new JComboBox<>(Sort.values());
    private final JTextField filter = new JTextField(14);
    private final JCheckBox crossOnly = new JCheckBox("Linked across desks only");
    private final JCheckBox unreadOnly = new JCheckBox("Unread only");
    private final BriefingPanel briefing;
    private final StoryBrowser saved;
    private final MapPanel map;
    private final WorldPanel world;
    private final AskPanel askPanel;
    private final TimelinePanel threadTimeline = new TimelinePanel();
    private final TimelinePanel lineTimeline = new TimelinePanel();
    private Analyzer.Result savedResult = new Analyzer.Result(List.of(), List.of(), 0);
    private final JButton fetchBtn = new JButton("Download news now");
    private final StoryBrowser stories;
    private final DefaultListModel<StoryThread> threadModel = new DefaultListModel<>();
    private final JList<StoryThread> threadList = new JList<>(threadModel);
    private final JEditorPane threadPane = StoryBrowser.htmlPane();
    private final DefaultListModel<Storyline> lineModel = new DefaultListModel<>();
    private final JList<Storyline> lineList = new JList<>(lineModel);
    private final JEditorPane linePane = StoryBrowser.htmlPane();
    private LinkWordsDialog wordsDialog;
    private CorrectionsDialog correctionsDialog;
    private final TopicsPanel topics;
    private final SearchPanel search;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel(" ");
    private final JProgressBar busy = new JProgressBar();
    private FeedHealthDialog healthDialog;
    private final WatchPanel watchPanel;
    private final MarketsPanel markets;
    private com.linkeddesk.alerts.Tray tray;
    /** Which watches each story in the current view matches (worked out after each analysis). */
    private volatile java.util.Map<StoryCluster, List<com.linkeddesk.alerts.Watches.Watch>> watchHits = java.util.Map.of();
    private volatile com.linkeddesk.alerts.Watches watchRef;

    public MainWindow(NewsService service) {
        super("The Linked Desk " + com.linkeddesk.app.AppPaths.VERSION + " — world news, linked");
        this.service = service;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // Fit the screen (also at 150-200% Windows scaling, where the usable area in Java pixels is smaller).
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1440, screen.width - 40), Math.min(900, screen.height - 40));
        setLocationByPlatform(true);
        if (screen.width < 1300) setExtendedState(MAXIMIZED_BOTH);
        stories = new StoryBrowser(this);
        topics = new TopicsPanel(this);
        search = new SearchPanel(this);
        search.topicsChanged = () -> topics.reload(null);
        briefing = new BriefingPanel(this);
        saved = new StoryBrowser(this);
        map = new MapPanel(this);
        world = new WorldPanel(this);
        askPanel = new AskPanel(this);
        watchPanel = new WatchPanel(this);
        markets = new MarketsPanel(this);
        threadTimeline.onOpen = c -> openStory(c.id);
        lineTimeline.onOpen = c -> openStory(c.id);

        desk.addItem("All desks");
        for (Category c : Category.values()) desk.addItem(c);
        int days = service.settings().windowDays();
        for (int i = 0; i < window.getItemCount(); i++) if (window.getItemAt(i).days() == days) window.setSelectedIndex(i);

        setJMenuBar(menus());

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        fetchBtn.setFont(fetchBtn.getFont().deriveFont(Font.BOLD));
        bar.add(fetchBtn);
        bar.addSeparator();
        bar.add(new JLabel("Show: ")); bar.add(window);
        bar.add(Box.createHorizontalStrut(6)); bar.add(desk);
        bar.add(Box.createHorizontalStrut(6)); bar.add(new JLabel("Sort: ")); bar.add(sort);
        bar.add(Box.createHorizontalStrut(6)); bar.add(new JLabel("Filter: ")); bar.add(filter);
        bar.add(Box.createHorizontalStrut(6)); bar.add(crossOnly);
        bar.add(Box.createHorizontalStrut(6)); bar.add(unreadOnly);
        bar.add(Box.createHorizontalGlue());
        JButton searchBtn = new JButton("Search all news…");
        searchBtn.addActionListener(e -> openSearch());
        bar.add(searchBtn);
        for (JComboBox<?> c : List.of(window, desk, sort)) c.setMaximumSize(c.getPreferredSize());
        filter.setMaximumSize(filter.getPreferredSize());
        filter.setToolTipText("<html>Narrows the stories shown in this window (press / to jump here). Also takes filters:<br>"
                + "source:bbc · desk:finance · after:2026-11-01 · after:2d · region:europe · is:unread · is:starred</html>");

        threadList.setCellRenderer(new ThreadRenderer());
        threadList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            StoryThread t = threadList.getSelectedValue();
            threadPane.setText(t == null ? Html.empty("Pick a thread on the left.") : Html.thread(t));
            threadTimeline.setStories(t == null ? List.of() : t.stories());
            threadPane.setCaretPosition(0);
        });
        threadPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription().startsWith("story:")) openStory(e.getDescription().substring(6));
        });
        JSplitPane threadRight = new JSplitPane(JSplitPane.VERTICAL_SPLIT, threadTimeline, new JScrollPane(threadPane));
        threadRight.setResizeWeight(0.3);
        JSplitPane threadSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(threadList), threadRight);
        threadSplit.setDividerLocation(520);
        threadPane.setText(Html.empty("Threads appear once stories are linked."));

        lineList.setCellRenderer(new LineRenderer());
        lineList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Storyline l = lineList.getSelectedValue();
            linePane.setText(l == null ? Html.empty("Pick a storyline on the left.") : Html.storyline(l));
            lineTimeline.setStories(l == null ? List.of() : l.stories());
            linePane.setCaretPosition(0);
        });
        linePane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription().startsWith("story:")) openStory(e.getDescription().substring(6));
        });
        JSplitPane lineRight = new JSplitPane(JSplitPane.VERTICAL_SPLIT, lineTimeline, new JScrollPane(linePane));
        lineRight.setResizeWeight(0.3);
        JSplitPane lineSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(lineList), lineRight);
        lineSplit.setDividerLocation(520);
        linePane.setText(Html.empty("Storylines appear when an event is reported again on later days."));

        tabs.addTab("Briefing", briefing);
        tabs.addTab("Stories", stories);
        tabs.addTab("Saved", saved);
        tabs.addTab("Watchlist", watchPanel);
        tabs.addTab("Storylines", lineSplit);
        tabs.addTab("Threads", threadSplit);
        tabs.addTab("Map", map);
        tabs.addTab("World", world);
        tabs.addTab("Ask", askPanel);
        tabs.addTab("Markets", markets);
        tabs.addTab("Topics", topics);
        tabs.addTab("Search", search);

        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setPreferredSize(new Dimension(160, 14));
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        south.add(status, BorderLayout.CENTER);
        south.add(busy, BorderLayout.EAST);

        getContentPane().add(bar, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);

        fetchBtn.addActionListener(e -> { if (downloading.get()) cancel(); else fetch(); });
        window.addActionListener(e -> {
            service.settings().reload();
            service.settings().setWindowDays(((TimeWindow) window.getSelectedItem()).days());
            service.settings().save();
            analyze(null);
        });
        desk.addActionListener(e -> refreshLists(null));
        sort.addActionListener(e -> refreshLists(null));
        crossOnly.addActionListener(e -> refreshLists(null));
        unreadOnly.addActionListener(e -> refreshLists(null));
        installShortcuts();
        installZoom();
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshLists(null); }
            public void removeUpdate(DocumentEvent e) { refreshLists(null); }
            public void changedUpdate(DocumentEvent e) { refreshLists(null); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) { reloadIfChangedElsewhere(); }
        });
    }

    private JMenuBar menus() {
        JMenuBar mb = new JMenuBar();
        JMenu news = new JMenu("News");
        news.add(item("Download news now", KeyEvent.VK_D, e -> fetch()));
        news.add(item("Export…", KeyEvent.VK_E, e -> exportCurrent()));
        news.add(item("Copy the open story", 0, e -> { StoryCluster c = openStory(); if (c != null) copyStory(c); else status.setText("Open a story first."); }));
        news.add(item("Weekly report…", 0, e -> openWeeklyReport()));
        news.addSeparator();
        news.add(item("Test all feeds", 0, e -> { openHealth(); }));
        news.add(item("Feed health…", 0, e -> openHealth()));
        news.addSeparator();
        news.add(item("Exit", 0, e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        JMenu tmenu = new JMenu("Topics");
        tmenu.add(item("Add topic…", 0, e -> { tabs.setSelectedComponent(topics); topics.addTopic(); }));
        tmenu.add(item("Show topics", 0, e -> tabs.setSelectedComponent(topics)));
        JMenu find = new JMenu("Search");
        find.add(item("Search all news…", KeyEvent.VK_F, e -> openSearch()));
        JMenu view = new JMenu("View");
        view.add(item("Briefing", 0, e -> tabs.setSelectedComponent(briefing)));
        view.add(item("Saved stories", 0, e -> tabs.setSelectedComponent(saved)));
        view.add(item("Watchlist", 0, e -> tabs.setSelectedComponent(watchPanel)));
        view.add(item("Markets", 0, e -> tabs.setSelectedComponent(markets)));
        view.add(item("Connection map", 0, e -> tabs.setSelectedComponent(map)));
        view.add(item("World map", 0, e -> tabs.setSelectedComponent(world)));
        view.add(item("Ask about the news", 0, e -> { tabs.setSelectedComponent(askPanel); askPanel.focusQuestion(); }));
        view.addSeparator();
        view.add(item("Logs…", KeyEvent.VK_L, e -> new LogsDialog(this).setVisible(true)));
        view.add(item("Last download report", 0, e -> showReport()));
        JMenu tools = new JMenu("Tools");
        tools.add(item("Settings…", 0, e -> openSettings()));
        tools.add(item("Sources (mute or weight outlets)…", 0, e -> openSources()));
        tools.add(item("Archive and backup…", 0, e -> openArchive()));
        tools.add(item("Watch a subject…", 0, e -> addWatch("")));
        tools.addSeparator();
        tools.add(item("Calibrate linking…", 0, e -> new CalibrationDialog(this, this).setVisible(true)));
        tools.add(item("Words behind links…", 0, e -> openWords()));
        tools.add(item("Your corrections…", 0, e -> openCorrections()));
        tools.addSeparator();
        tools.add(item("Claude usage…", 0, e -> new UsageDialog(this, service).setVisible(true)));
        JMenu help = new JMenu("Help");
        help.add(item("Keyboard shortcuts", 0, e -> showShortcuts()));
        mb.add(news); mb.add(tmenu); mb.add(find); mb.add(view); mb.add(tools); mb.add(help);
        return mb;
    }

    private JMenuItem item(String text, int key, java.awt.event.ActionListener a) {
        JMenuItem i = new JMenuItem(text);
        if (key != 0) i.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
        i.addActionListener(a);
        return i;
    }

    /** Loads saved news, shows it, downloads if it's stale, and starts the daily timer. */
    public void start() {
        setBusy("Loading saved news…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { service.loadAll(); return null; }
            @Override protected void done() {
                try { get(); } catch (Exception ex) { status.setText("Could not read saved news: " + rootMessage(ex)); }
                topics.reload(null);
                if (service.settings().showBriefing()) tabs.setSelectedComponent(briefing);
                else tabs.setSelectedComponent(stories);
                briefing.reload();
                reloadSaved();
                watchPanel.reload();
                markets.reload();
                installTray();
                indexInBackground();
                analyze(() -> {
                    if (service.settings().fetchOnStart() && DailyScheduler.isStale(service.settings())) fetchDaily();
                });
            }
        }.execute();
        new DailyScheduler(service.settings(), () -> SwingUtilities.invokeLater(this::fetchDaily)).start();
        // Notifications held during quiet hours are shown once they end.
        Timer held = new Timer(5 * 60_000, e -> {
            if (tray == null) return;
            new Thread(() -> { try { service.flushHeldAlerts(tray); } catch (Exception ignored) { } }, "held-alerts").start();
        });
        held.start();
    }

    public void bringToFront() {
        if ((getExtendedState() & ICONIFIED) != 0) setExtendedState(getExtendedState() & ~ICONIFIED);
        setVisible(true);
        toFront();
        requestFocus();
    }

    // ------------------------------------------------------------------ Host

    @Override public NewsService service() { return service; }

    @Override public void status(String text) { status.setText(text); }

    @Override public void articlesChanged() { analyze(null); }

    @Override public void savedChanged() {
        reloadSaved();
        stories.refreshDetail();
        briefing.refreshPage();
    }

    private void reloadSaved() {
        new SwingWorker<Analyzer.Result, Void>() {
            @Override protected Analyzer.Result doInBackground() throws Exception { return service.analyzeSaved(); }
            @Override protected void done() {
                try {
                    savedResult = get();
                    StoryCluster sel = saved.selected();
                    saved.setStories(savedResult.stories(), savedResult.stories(), sel == null ? null : sel.id,
                            "Nothing saved yet. Click ☆ Star on any story to keep it here.");
                    tabs.setTitleAt(tabs.indexOfComponent(saved), "Saved (" + savedResult.stories().size() + ")");
                } catch (Exception ex) { status.setText("Could not show saved stories: " + rootMessage(ex)); }
            }
        }.execute();
    }

    @Override public void correctionsChanged() {
        analyze(null);
        briefing.reload();
        reloadSaved();
        topics.refresh();
        search.refresh();
        if (correctionsDialog != null && correctionsDialog.isShowing()) correctionsDialog.reload();
    }

    private void openWords() {
        if (wordsDialog == null || !wordsDialog.isDisplayable()) wordsDialog = new LinkWordsDialog(this, this);
        wordsDialog.show(result);
        wordsDialog.setVisible(true);
        wordsDialog.toFront();
    }

    private void openCorrections() {
        if (correctionsDialog == null || !correctionsDialog.isDisplayable()) correctionsDialog = new CorrectionsDialog(this, this);
        correctionsDialog.reload();
        correctionsDialog.setVisible(true);
        correctionsDialog.toFront();
    }

    @Override public <T> void runDownload(String busyText, DownloadJob<T> job, Consumer<T> after) {
        if (!downloading.compareAndSet(false, true)) {
            JOptionPane.showMessageDialog(this, "A download is already running. Wait for it to finish or click Cancel download.");
            return;
        }
        cancelRequested.set(false);
        fetchBtn.setText("Cancel download");
        setBusy(busyText);
        new SwingWorker<T, String>() {
            @Override protected T doInBackground() throws Exception { return job.run(this::publish, cancelRequested::get); }
            @Override protected void process(List<String> chunks) { status.setText(chunks.get(chunks.size() - 1) + "…"); }
            @Override protected void done() {
                downloading.set(false);
                fetchBtn.setText("Download news now");
                fetchBtn.setEnabled(true);
                busy.setVisible(false);
                try {
                    T r = get();
                    if (r instanceof FetchReport fr) lastReport = fr;
                    if (r instanceof NewsService.SearchResult) { /* report kept by the service log */ }
                    after.accept(r);
                    if (r instanceof FetchReport fr && fr.cancelled) status.setText("Download cancelled. " + fr.summary());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    if (c instanceof NewsService.BusyException) status.setText(c.getMessage());
                    else { status.setText("Download failed: " + rootMessage(ex)); com.linkeddesk.app.Logs.error("Download from the window", c); }
                    updateStatus();
                }
            }
        }.execute();
    }

    private void cancel() {
        cancelRequested.set(true);
        fetchBtn.setEnabled(false);
        status.setText("Cancelling… (articles already downloaded are kept)");
    }

    /** The digest and link checks after the daily download (in the background; within the budget). */
    private void runDailyClaude() {
        service.settings().reload();
        if (!service.settings().hasApiKey()) return;
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() { return service.dailyClaude(); }
            @Override protected void done() {
                try { status.setText(get()); com.linkeddesk.app.Logs.run("claude", get(), "", 0); } catch (Exception ignored) { }
                briefing.reload();
                stories.refreshDetail();
                analyze(null);       // translations change grouping
                new Thread(() -> { try { service.publishPhone(); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Writing the phone briefing", ex); } }, "phone").start();
            }
        }.execute();
    }

    private void fetch() { fetch(false); }

    /** The daily download (timer or start-up): afterwards the briefing opens, if you want it to. */
    private void fetchDaily() { fetch(true); }

    private void fetch(boolean daily) {
        if (downloading.get()) return;
        runDownload("Downloading news…", (progress, cancelled) -> service.fetchNow(progress, cancelled), r -> {
            topics.reload(null);
            if (healthDialog != null && healthDialog.isShowing()) healthDialog.reload();
            analyze(null);
            briefing.reload();
            reloadSaved();
            if (daily && service.settings().showBriefing()) tabs.setSelectedComponent(briefing);
            if (daily) runDailyClaude();
            watchPanel.reload();
            markets.reload();
            afterDownload(r, daily);
        });
    }

    @Override public void explain(StoryCluster from, StoryLink link) {
        service.settings().reload();
        if (!service.settings().hasApiKey()) {
            JOptionPane.showMessageDialog(this, "To have Claude explain links, add an Anthropic API key in Tools > Settings.\n"
                    + "Until then, the \"Shared\" terms show why the app linked these stories.", "Ask Claude", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        setBusy("Asking Claude about this link…");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return service.explainLink(link); }
            @Override protected void done() {
                busy.setVisible(false);
                updateStatus();
                String text;
                try { text = get() + "\n\n— " + service.claude().summary(); } catch (Exception ex) { text = firstMessage(ex); }
                JTextArea area = new JTextArea(text, 14, 60);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setEditable(false);
                area.setFont(UIManager.getFont("Label.font").deriveFont(UIManager.getFont("Label.font").getSize2D() * 1.1f));
                area.setCaretPosition(0);
                JOptionPane.showMessageDialog(MainWindow.this, new JScrollPane(area), "Why these stories may be linked", JOptionPane.PLAIN_MESSAGE);
            }
        }.execute();
    }

    // ------------------------------------------------------------------ analysis and lists

    private void analyze(Runnable after) {
        int days = ((TimeWindow) window.getSelectedItem()).days();
        int run = ++analysisRun;
        setBusy("Grouping and linking stories…");
        StoryCluster sel = stories.selected();
        String selected = sel == null ? null : sel.id;
        new SwingWorker<Analyzer.Result, Void>() {
            @Override protected Analyzer.Result doInBackground() { return service.analyze(days); }
            @Override protected void done() {
                if (run != analysisRun) return;           // a newer analysis has started; skip this one
                try {
                    result = get();
                    refreshLists(selected);
                    watchesChanged();          // watch marks follow a moment later
                } catch (Exception ex) {
                    status.setText("Analysis failed: " + rootMessage(ex));
                }
                if (!downloading.get()) busy.setVisible(false);
                updateStatus();
                if (after != null) after.run();
            }
        }.execute();
    }

    private void reloadIfChangedElsewhere() {
        if (downloading.get()) return;
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() throws Exception {
                service.settings().reload();
                service.health().load();
                boolean changed = service.store().reloadIfChanged();
                if (changed) service.topicHits().load();
                return changed;
            }
            @Override protected void done() {
                try { if (get()) { topics.reload(null); analyze(null); } } catch (Exception ignored) { }
            }
        }.execute();
    }

    private void refreshLists(String keepSelected) {
        String raw = filter.getText().trim();
        com.linkeddesk.analysis.SearchQuery fq = new com.linkeddesk.analysis.SearchQuery(raw);
        // With field filters (source:, desk:, after:…) the box works like Search; otherwise it matches any text.
        boolean fields = !fq.fields().isEmpty();
        String q = fields ? "" : raw.toLowerCase(Locale.ROOT);
        java.util.function.Predicate<com.linkeddesk.model.Article> ft = fields ? service.fieldTest(fq) : a -> true;
        Object d = desk.getSelectedItem();
        var reads = service.reads();
        List<StoryCluster> list = new ArrayList<>();
        for (StoryCluster c : result.stories()) {
            if (d instanceof Category cat && !c.inDesk(cat)) continue;
            if (crossOnly.isSelected() && !c.hasCrossDeskLink()) continue;
            if (unreadOnly.isSelected() && !reads.isUnread(c)) continue;
            if (!q.isEmpty() && !matches(c, q)) continue;
            if (fields && c.articles.stream().noneMatch(a -> ft.test(a) && (!fq.hasWords() || fq.matches(a)))) continue;
            list.add(c);
        }
        filter.setForeground(fq.problems().isEmpty() ? UIManager.getColor("TextField.foreground") : Theme.c(Theme.bad()));
        filter.setToolTipText(fq.problems().isEmpty() ? filter.getToolTipText() : String.join("; ", fq.problems()));
        Comparator<StoryCluster> cmp = switch ((Sort) sort.getSelectedItem()) {
            case NEWEST -> Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed();
            case LINKED -> Comparator.comparingInt((StoryCluster c) -> c.links.size()).reversed()
                    .thenComparing(Comparator.comparingInt(StoryCluster::sourceCount).reversed());
            case COVERED -> Comparator.comparingDouble((StoryCluster c) -> service.sources().coverage(c)).reversed()
                    .thenComparing(Comparator.comparingLong((StoryCluster c) -> c.lastSeen).reversed());
        };
        list.sort(cmp);
        String keep = keepSelected != null ? keepSelected : stories.selected() == null ? null : stories.selected().id;
        stories.setStories(list, result.stories(), keep, result.articleCount() == 0
                ? "No news saved yet. Click \"Download news now\"." : "No stories match these filters.");

        List<StoryThread> threads = new ArrayList<>();
        for (StoryThread t : result.threads()) {
            if (d instanceof Category cat && !t.desks().contains(cat)) continue;
            if (!q.isEmpty() && !t.topic().toLowerCase(Locale.ROOT).contains(q)
                    && t.stories().stream().noneMatch(c -> matches(c, q))) continue;
            threads.add(t);
        }
        threadModel.clear();
        threadModel.addAll(threads);
        if (!threads.isEmpty() && threadList.getSelectedIndex() < 0) threadList.setSelectedIndex(0);
        List<Storyline> lines = new ArrayList<>();
        for (Storyline l : result.storylines()) {
            if (d instanceof Category cat && l.stories().stream().noneMatch(c -> c.inDesk(cat))) continue;
            if (!q.isEmpty() && l.stories().stream().noneMatch(c -> matches(c, q))) continue;
            lines.add(l);
        }
        Storyline keepLine = lineList.getSelectedValue();
        lineModel.clear();
        lineModel.addAll(lines);
        int li = keepLine == null ? -1 : lines.stream().map(l -> l.first().id).toList().indexOf(keepLine.first().id);
        if (!lines.isEmpty()) lineList.setSelectedIndex(Math.max(0, li));
        else linePane.setText(Html.empty("Storylines appear when an event is reported again on later days."));
        if (wordsDialog != null && wordsDialog.isShowing()) wordsDialog.show(result);
        long unread = list.stream().filter(reads::isUnread).count();
        tabs.setTitleAt(tabs.indexOfComponent(stories), "Stories (" + list.size() + (unread > 0 && unread < list.size() ? ", " + unread + " unread" : "") + ")");
        tabs.setTitleAt(tabs.indexOfComponent(lineList.getParent().getParent().getParent()), "Storylines (" + lines.size() + ")");
        tabs.setTitleAt(tabs.indexOfComponent(threadList.getParent().getParent().getParent()), "Threads (" + threads.size() + ")");
        if (mapResult != result) { mapResult = result; map.setResult(result); }
        Category deskNow = d instanceof Category cat ? cat : null;
        if (worldResult != result || worldDesk != deskNow) { worldResult = result; worldDesk = deskNow; world.setResult(result, deskNow); }
        updateStatus();
    }

    private Analyzer.Result mapResult, worldResult;
    private Category worldDesk;

    private static boolean matches(StoryCluster c, String q) {
        if (c.headline.toLowerCase(Locale.ROOT).contains(q)) return true;
        return c.articles.stream().anyMatch(a -> a.title.toLowerCase(Locale.ROOT).contains(q)
                || a.summary.toLowerCase(Locale.ROOT).contains(q) || a.source.toLowerCase(Locale.ROOT).contains(q));
    }

    private void openStory(String id) {
        tabs.setSelectedComponent(stories);
        unreadOnly.setSelected(false);
        if (!stories.contains(id)) {
            desk.setSelectedIndex(0);
            crossOnly.setSelected(false);
            filter.setText("");
        }
        refreshLists(id);
        stories.select(id);
    }

    private void openSearch() {
        tabs.setSelectedComponent(search);
        search.focusQuery();
    }

    private void openHealth() {
        if (healthDialog == null || !healthDialog.isDisplayable()) healthDialog = new FeedHealthDialog(this, this);
        healthDialog.reload();
        healthDialog.setVisible(true);
        healthDialog.toFront();
    }

    // ------------------------------------------------------------------ export, report, archive (P6)

    @Override public void copyStory(StoryCluster c) {
        ExportUi.copy(com.linkeddesk.export.Exporter.story(c, service.regions()));
        status.setText("Copied \"" + c.headline + "\" with its " + c.articles.size() + (c.articles.size() == 1 ? " source" : " sources")
                + ". Paste into Word, Outlook or Gmail and the links come along.");
    }

    @Override public void exportStory(StoryCluster c) {
        ExportUi.save(this, this, com.linkeddesk.export.Exporter.story(c, service.regions()), List.of(c));
    }

    /** The story open in the current tab, if any. */
    private StoryCluster openStory() {
        Component t = tabs.getSelectedComponent();
        StoryBrowser b = t == saved ? saved : t == search ? search.browser() : t == watchPanel ? watchPanel.browser() : t == markets ? markets.browser() : stories;
        return b.selected();
    }

    /** News > Export: what's on screen (the briefing, a thread, a storyline, the open story or the whole list). */
    private void exportCurrent() {
        Component t = tabs.getSelectedComponent();
        var reg = service.regions();
        if (t == briefing) {
            if (briefing.briefing() == null) { status.setText("The briefing isn't ready yet."); return; }
            ExportUi.save(this, this, com.linkeddesk.export.Exporter.briefing(briefing.briefing(), service.todaysDigest(), reg),
                    briefing.briefing().desks().values().stream().flatMap(l -> l.stream().map(com.linkeddesk.analysis.Briefing.Ranked::story)).toList());
            return;
        }
        if (threadList.isShowing() && threadList.getSelectedValue() != null) {
            StoryThread th = threadList.getSelectedValue();
            ExportUi.save(this, this, com.linkeddesk.export.Exporter.thread(th, reg), th.stories());
            return;
        }
        if (lineList.isShowing() && lineList.getSelectedValue() != null) {
            Storyline l = lineList.getSelectedValue();
            ExportUi.save(this, this, com.linkeddesk.export.Exporter.stories("Storyline: " + l.latest().headline, l.stories(), reg), l.stories());
            return;
        }
        StoryBrowser b = t == saved ? saved : t == stories ? stories : t == search ? search.browser() : t == watchPanel ? watchPanel.browser()
                : t == markets ? markets.browser() : null;
        List<StoryCluster> list = b == null ? List.of() : b.shown();
        StoryCluster open = b == null ? stories.selected() : b.selected();
        if (open == null && list.isEmpty()) { status.setText("Open a story, a thread or the briefing to export it."); return; }
        int choice = 0;
        if (!list.isEmpty()) {
            Object[] opts = open == null ? new Object[]{"All " + list.size() + " stories in the list", "Cancel"}
                    : new Object[]{"The open story", "All " + list.size() + " stories in the list", "Cancel"};
            int c = JOptionPane.showOptionDialog(this, "What would you like to export?", "Export", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (c < 0 || c == opts.length - 1) return;
            choice = open == null ? 1 : c;
        }
        if (choice == 0) exportStory(open);
        else {
            String title = t == saved ? "Saved stories" : t == search ? "Search results" : t == watchPanel ? "Watchlist" : t == markets ? "Markets"
                    : "Stories · " + window.getSelectedItem() + (desk.getSelectedItem() instanceof Category cat ? " · " + cat.label() : "");
            ExportUi.save(this, this, com.linkeddesk.export.Exporter.stories(title, list, reg), list);
        }
    }

    private WeeklyReportDialog reportDialog;

    private void openWeeklyReport() {
        if (reportDialog == null || !reportDialog.isDisplayable()) reportDialog = new WeeklyReportDialog(this, this);
        reportDialog.setVisible(true);
        reportDialog.toFront();
    }

    private void openArchive() {
        ArchiveDialog d = new ArchiveDialog(this, this);
        d.setVisible(true);
        if (d.restored) {
            Theme.refreshAll(service.settings());
            topics.reload(null);
            watchPanel.reload();
            markets.reload();
            correctionsChanged();
        }
    }

    /** Keeps the search index up to date in the background (the first time it indexes every month). */
    private void indexInBackground() {
        Thread t = new Thread(() -> {
            try { service.refreshIndex(); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Updating the search index", ex); }
        }, "search-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void openSources() {
        SourcesDialog d = new SourcesDialog(this, this);
        d.setVisible(true);
        if (d.changed()) { analyze(null); briefing.reload(); search.refresh(); }
    }

    // ------------------------------------------------------------------ watchlist, tray, email (P5)

    @Override public List<String> watchesFor(StoryCluster c) {
        List<com.linkeddesk.alerts.Watches.Watch> w = watchHits.get(c);
        if (w == null) return List.of();
        List<String> out = new ArrayList<>();
        for (var x : w) out.add(x.name());
        return out;
    }

    @Override public boolean newForWatch(StoryCluster c) {
        List<com.linkeddesk.alerts.Watches.Watch> w = watchHits.get(c);
        if (w == null) return false;
        var ws = watchRef;
        if (ws == null) return false;
        for (var x : w) { long seen = ws.lastSeen(x.name()); if (seen > 0 && WatchPanel.newSince(c, seen)) return true; }
        return false;
    }

    @Override public void watchesChanged() {
        List<StoryCluster> now = result.stories();
        new SwingWorker<java.util.Map<StoryCluster, List<com.linkeddesk.alerts.Watches.Watch>>, Void>() {
            @Override protected java.util.Map<StoryCluster, List<com.linkeddesk.alerts.Watches.Watch>> doInBackground() {
                watchRef = service.watches();
                return watchRef.stories(now);
            }
            @Override protected void done() {
                try { watchHits = get(); } catch (Exception ignored) { }
                stories.refreshRows();
                saved.refreshRows();
                updateWatchTab();
            }
        }.execute();
    }

    @Override public void addWatch(String words) {
        tabs.setSelectedComponent(watchPanel);
        watchPanel.addWatch(words);
    }

    private void updateWatchTab() {
        int i = tabs.indexOfComponent(watchPanel);
        int n = watchPanel.newTotal();
        if (i >= 0) tabs.setTitleAt(i, n > 0 ? "Watchlist (" + n + ")" : "Watchlist");
    }

    /** The tray icon: click to open the watchlist; right-click for Open / Download now / Watchlist. */
    private void installTray() {
        if (!com.linkeddesk.alerts.Tray.supported()) return;
        Runnable open = () -> SwingUtilities.invokeLater(() -> {
            setExtendedState(getExtendedState() & ~ICONIFIED);
            setVisible(true);
            toFront();
            tabs.setSelectedComponent(watchPanel);
        });
        tray = new com.linkeddesk.alerts.Tray(open);
        PopupMenu menu = new PopupMenu();
        MenuItem o = new MenuItem("Open The Linked Desk");
        o.addActionListener(e -> SwingUtilities.invokeLater(() -> { setExtendedState(getExtendedState() & ~ICONIFIED); toFront(); }));
        MenuItem d = new MenuItem("Download news now");
        d.addActionListener(e -> SwingUtilities.invokeLater(this::fetch));
        MenuItem w = new MenuItem("Watchlist");
        w.addActionListener(e -> open.run());
        menu.add(o); menu.add(d); menu.add(w);
        if (!tray.install(menu)) tray = null;
    }

    /** After a download: one grouped notification for new watchlist matches; after the daily one, the email. */
    private void afterDownload(Object r, boolean daily) {
        if (!(r instanceof FetchReport fr)) return;
        com.linkeddesk.alerts.Alerts.Display display = tray != null ? tray : (title, text) -> {
            SwingUtilities.invokeLater(() -> status.setText(title + ": " + text.replace('\n', ' ')));
            return true;
        };
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                StringBuilder out = new StringBuilder();
                try {
                    var a = service.notifyAfterDownload(fr, display);
                    if (a != null && !a.isEmpty()) out.append(a.title()).append(". ");
                } catch (Exception ex) { com.linkeddesk.app.Logs.error("Notification after download", ex); }
                if (daily) {
                    try {
                        String m = service.emailBriefing(false);
                        if (!m.startsWith("Email: off")) out.append(m);
                    } catch (Exception ex) {
                        out.append("The briefing email wasn't sent: ").append(ex.getMessage()).append(" (View > Logs).");
                    }
                    String m = service.maintenance();        // compress old months, index, weekly backup
                    if (!m.isEmpty()) out.append(' ').append(m);
                } else {
                    try { service.refreshIndex(); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Updating the search index", ex); }
                }
                return out.toString().strip();
            }
            @Override protected void done() {
                try { String m = get(); if (!m.isEmpty()) status.setText(m); } catch (Exception ignored) { }
            }
        }.execute();
    }

    // ------------------------------------------------------------------ zoom (Ctrl + mouse wheel)

    private int zoomTarget = -1;
    private final Timer zoomTimer = new Timer(250, e -> applyZoom());

    /** Ctrl + mouse wheel and Ctrl +/-/0 anywhere in the app change the text size (saved like the Settings choice). */
    private void installZoom() {
        zoomTimer.setRepeats(false);
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override protected void dispatchEvent(AWTEvent e) {
                if (e instanceof java.awt.event.MouseWheelEvent w && w.isControlDown() && !w.isAltDown()) {
                    double r = w.getPreciseWheelRotation();
                    if (r != 0) zoomBy(r < 0 ? 1 : -1);        // wheel away from you = bigger, as in a browser
                    return;                                    // don't scroll as well
                }
                if (e instanceof KeyEvent k && k.getID() == KeyEvent.KEY_PRESSED && k.isControlDown() && !k.isAltDown() && !k.isShiftDown()) {
                    int c = k.getKeyCode();
                    if (c == KeyEvent.VK_ADD || c == KeyEvent.VK_EQUALS || c == KeyEvent.VK_PLUS) { zoomBy(1); return; }
                    if (c == KeyEvent.VK_SUBTRACT || c == KeyEvent.VK_MINUS) { zoomBy(-1); return; }
                    if (c == KeyEvent.VK_0 || c == KeyEvent.VK_NUMPAD0) { zoomTo(100); return; }
                }
                super.dispatchEvent(e);
            }
        });
    }

    private void zoomBy(int dir) {
        int now = zoomTarget > 0 ? zoomTarget : service.settings().textSize();
        zoomTo(Theme.step(now, dir));
    }

    private void zoomTo(int pct) {
        zoomTarget = pct;
        status.setText("Text size " + pct + "%  (Ctrl + mouse wheel to change, Ctrl 0 for 100%)");
        zoomTimer.restart();      // apply once the wheel stops, so the window isn't redrawn at every notch
    }

    private void applyZoom() {
        int pct = zoomTarget;
        zoomTarget = -1;
        if (pct <= 0) return;
        service.settings().reload();
        if (pct == service.settings().textSize()) return;
        service.settings().setTextSize(pct);
        service.settings().save();
        applyLook();
        status.setText("Text size " + pct + "%");
    }

    /** Redraws everything after the colours or text size changed. */
    private void applyLook() {
        Theme.refreshAll(service.settings());
        stories.refreshDetail();
        saved.refreshDetail();
        briefing.refreshPage();
        map.refreshPage();
        world.refreshPage();
        askPanel.refreshPage();
        watchPanel.refreshPage();
        markets.refreshPage();
        stories.refreshRows();
        saved.refreshRows();
        refreshLists(null);
    }

    private void openSettings() {
        SettingsDialog d = new SettingsDialog(this, service.settings());
        d.testEmail = () -> service.emailBriefing(true);
        d.writePhone = () -> { var p = service.publishPhone(); return p == null ? "No folder chosen." : p.toString(); };
        d.setVisible(true);
        if (d.wasSaved()) stories.refreshDetail();
        if (d.lookChanged()) applyLook();
    }

    private void showReport() {
        String text = lastReport == null
                ? "No download has run since the app opened.\nSee View > Logs for every download in the last 30 days."
                : lastReport.detail();
        JTextArea area = new JTextArea(text, 24, 100);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Last download report", JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ keyboard shortcuts

    private static final String[][] SHORTCUTS = {
            {"J / K", "Next / previous story in the list"},
            {"/", "Jump to the filter box (Stories) or the search box (Search)"},
            {"S", "Star or unstar the open story"},
            {"U", "Mark the open story unread"},
            {"F", "Download news now"},
            {"B", "Open the briefing"},
            {"M", "Open the connection map"},
            {"W", "Open the world map"},
            {"A", "Ask Claude about your news"},
            {"L", "Open your watchlist"},
            {"Ctrl + mouse wheel", "Bigger or smaller text (also Ctrl + / Ctrl -; Ctrl 0 = 100%)"},
            {"?", "Show these shortcuts"},
            {"Ctrl+D / Ctrl+F / Ctrl+L / Ctrl+E", "Download / Search all news / Logs / Export what's on screen"},
    };

    /** Single-key shortcuts, active whenever you're not typing in a text box. */
    private void installShortcuts() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_TYPED || !isActive()) return false;
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) return false;
            Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (f instanceof javax.swing.text.JTextComponent t && t.isEditable()) return false;
            return shortcut(e.getKeyChar());
        });
    }

    /** Runs a shortcut key; true if the key was one. */
    boolean shortcut(char ch) {
        StoryBrowser b = tabs.getSelectedComponent() == saved ? saved : stories;
        switch (Character.toLowerCase(ch)) {
            case 'j' -> { if (tabs.getSelectedComponent() != saved) tabs.setSelectedComponent(stories); b.step(1); }
            case 'k' -> { if (tabs.getSelectedComponent() != saved) tabs.setSelectedComponent(stories); b.step(-1); }
            case '/' -> {
                if (tabs.getSelectedComponent() == search) search.focusQuery();
                else { tabs.setSelectedComponent(stories); filter.requestFocusInWindow(); filter.selectAll(); }
            }
            case 's' -> toggleStar(b.selected());
            case 'u' -> {
                StoryCluster c = b.selected();
                if (c == null) return true;
                try { service.reads().markUnread(c); savedChanged(); refreshLists(null); status.setText("Marked unread."); }
                catch (Exception ex) { status.setText("Could not save: " + rootMessage(ex)); }
            }
            case 'f' -> fetch();
            case 'b' -> tabs.setSelectedComponent(briefing);
            case 'm' -> tabs.setSelectedComponent(map);
            case 'w' -> tabs.setSelectedComponent(world);
            case 'a' -> { tabs.setSelectedComponent(askPanel); askPanel.focusQuestion(); }
            case 'l' -> tabs.setSelectedComponent(watchPanel);
            case '?' -> showShortcuts();
            default -> { return false; }
        }
        return true;
    }

    private void toggleStar(StoryCluster c) {
        if (c == null) return;
        try {
            var reads = service.reads();
            if (reads.isStarred(c)) { reads.unstar(c); status.setText("Removed from Saved."); }
            else { reads.star(c); status.setText("Starred. Find it any time in the Saved tab."); }
            savedChanged();
            refreshLists(null);
        } catch (Exception ex) { status.setText("Could not save: " + rootMessage(ex)); }
    }

    private void showShortcuts() {
        StringBuilder sb = new StringBuilder("<html><table>");
        for (String[] k : SHORTCUTS) sb.append("<tr><td><b>").append(Html.esc(k[0])).append("</b></td><td>").append(Html.esc(k[1])).append("</td></tr>");
        sb.append("</table><br>Single keys work when you're not typing in a box.</html>");
        JOptionPane.showMessageDialog(this, sb.toString(), "Keyboard shortcuts", JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ helpers

    private void setBusy(String msg) {
        status.setText(msg);
        busy.setVisible(true);
    }

    private void updateStatus() {
        if (busy.isVisible()) return;
        long last = service.settings().lastFetch();
        long linked = result.stories().stream().filter(c -> !c.links.isEmpty()).count();
        int damaged = service.store().damagedLines();
        status.setText(String.format("%,d articles saved in total · %,d in view, grouped into %,d stories (%,d linked) · last download: %s%s%s",
                service.store().size(), result.articleCount(), result.stories().size(), linked,
                last == 0 ? "never" : STAMP.format(Instant.ofEpochMilli(last)),
                lastReport != null && lastReport.failures() > 0 ? " · " + lastReport.failures() + " feeds failed (News > Feed health)" : "",
                damaged > 0 ? " · " + damaged + " damaged line(s) in the archive were skipped" : ""));
    }

    private static String firstMessage(Throwable t) {
        while (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? rootMessage(t) : t.getMessage();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static final class LineRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(list, value, index, sel, focus);
            Storyline l = (Storyline) value;
            int w = Math.max(200, list.getParent() != null ? list.getParent().getWidth() - 40 : list.getWidth() - 40);
            setText("<html><div style='width:" + Html.cssPx(w) + "px'><b>" + Html.esc(l.latest().headline) + "</b><br>"
                    + "<span style='color:" + (sel ? "#ffffff" : Theme.muted()) + "'>" + l.stories().size() + " stories over " + l.days()
                    + (l.days() == 1 ? " day" : " days") + " · " + l.articleCount() + " articles · started "
                    + Html.esc(Html.ago(l.first().firstSeen)) + "</span></div></html>");
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 6, 1, 0, Theme.desk(l.latest().category)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 6)));
            return this;
        }
    }

    private static final class ThreadRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(list, value, index, sel, focus);
            StoryThread t = (StoryThread) value;
            StringBuilder desks = new StringBuilder();
            for (Category c : t.desks()) {
                if (desks.length() > 0) desks.append(", ");
                desks.append(c.label());
            }
            setText("<html><b>" + Html.esc(t.topic()) + "</b> — " + t.stories().size() + " stories<br>"
                    + "<span style='color:" + (sel ? "#ffffff" : Theme.muted()) + "'>" + Html.esc(desks.toString()) + "</span></html>");
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()),
                    BorderFactory.createEmptyBorder(6, 10, 6, 6)));
            return this;
        }
    }
}
