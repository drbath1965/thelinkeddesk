package com.linkeddesk.ui;

import com.linkeddesk.alerts.Tickers;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * The Markets tab (ALR-F05): the tickers you follow, and for each one the stories that mention it (by symbol such as
 * $NVDA or NASDAQ: NVDA, or by company name), newest day first. Stories about a followed ticker also appear on the
 * Finance desk.
 */
final class MarketsPanel extends JPanel {
    static final int DAYS = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d");
    private final Host host;
    private final DefaultListModel<Tickers.Ticker> model = new DefaultListModel<>();
    private final JList<Tickers.Ticker> list = new JList<>(model);
    private final StoryBrowser browser;
    StoryBrowser browser() { return browser; }
    private final JLabel info = new JLabel(" ");
    private final Map<String, int[]> counts = new HashMap<>();   // symbol -> {stories, today}
    private final JButton remove = new JButton("Remove");
    private int run;

    MarketsPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        browser = new StoryBrowser(host);
        browser.setStories(List.of(), List.of(), null, "Add a ticker you follow, such as NVDA or LMT.");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                Tickers.Ticker t = (Tickers.Ticker) v;
                int[] c = counts.get(t.symbol());
                String sub = c == null ? "counting…" : c[0] + (c[0] == 1 ? " story" : " stories") + " in " + DAYS + " days" + (c[1] > 0 ? " · " + c[1] + " today" : "");
                String names = t.names().isEmpty() ? "symbol only" : String.join(", ", t.names());
                setText("<html><b>" + Html.esc(t.symbol()) + "</b> <span style='color:" + (s ? "#ffffff" : Theme.muted()) + "'>" + Html.esc(names) + "</span><br>"
                        + "<span style='color:" + (s ? "#ffffff" : Theme.note()) + "'>" + sub + "</span></html>");
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()), BorderFactory.createEmptyBorder(6, 10, 6, 6)));
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) open(list.getSelectedValue()); });
        JButton add = new JButton("Add ticker…");
        add.addActionListener(e -> addTicker());
        remove.addActionListener(e -> removeSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(add); buttons.add(remove);
        JLabel intro = new JLabel("<html><div style='width:" + Html.cssPx(260) + "px;color:" + Theme.note() + "'>Tickers you follow. Stories that name the "
                + "company or its symbol are listed here by day and also shown on the Finance desk.</div></html>");
        intro.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel left = new JPanel(new BorderLayout());
        left.add(intro, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout());
        info.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        right.add(info, BorderLayout.NORTH);
        right.add(browser, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(300);
        add(split, BorderLayout.CENTER);
    }

    void reload() {
        Tickers tk = host.service().tickers();
        Tickers.Ticker sel = list.getSelectedValue();
        model.clear();
        model.addAll(tk.all());
        remove.setEnabled(!model.isEmpty());
        for (int i = 0; i < model.size(); i++) if (sel != null && model.get(i).symbol().equals(sel.symbol())) list.setSelectedIndex(i);
        if (model.isEmpty()) browser.setStories(List.of(), List.of(), null, "Add a ticker you follow, such as NVDA or LMT.");
        int my = ++run;
        new SwingWorker<Map<String, int[]>, Void>() {
            @Override protected Map<String, int[]> doInBackground() throws Exception {
                Map<String, int[]> m = new HashMap<>();
                LocalDate today = LocalDate.now();
                for (Tickers.Ticker t : tk.all()) {
                    List<StoryCluster> r = host.service().tickerStories(t.symbol(), DAYS).stories();
                    int td = 0;
                    for (StoryCluster c : r) if (day(c).equals(today)) td++;
                    m.put(t.symbol(), new int[]{r.size(), td});
                }
                return m;
            }
            @Override protected void done() {
                if (my != run) return;
                try { counts.clear(); counts.putAll(get()); list.repaint(); } catch (Exception ignored) { }
            }
        }.execute();
    }

    static LocalDate day(StoryCluster c) { return Instant.ofEpochMilli(c.lastSeen).atZone(ZoneId.systemDefault()).toLocalDate(); }

    private void open(Tickers.Ticker t) {
        if (t == null) return;
        info.setText("Stories about " + t.label() + "…");
        new SwingWorker<Analyzer.Result, Void>() {
            @Override protected Analyzer.Result doInBackground() throws Exception { return host.service().tickerStories(t.symbol(), DAYS); }
            @Override protected void done() {
                try {
                    List<StoryCluster> s = new ArrayList<>(get().stories());
                    s.sort(Comparator.comparing(MarketsPanel::day).reversed().thenComparing(Comparator.comparingInt(StoryCluster::sourceCount).reversed()));
                    // A day label above the first story of each day.
                    Set<StoryCluster> firsts = new HashSet<>();
                    LocalDate last = null;
                    Map<LocalDate, Integer> perDay = new LinkedHashMap<>();
                    for (StoryCluster c : s) {
                        LocalDate d = day(c);
                        if (!d.equals(last)) firsts.add(c);
                        last = d;
                        perDay.merge(d, 1, Integer::sum);
                    }
                    browser.setTags(c -> firsts.contains(c) ? dayLabel(day(c)) + " · " + perDay.get(day(c)) : null, false);
                    browser.setStories(s, get().stories(), null, "No stories about " + t.label() + " in the last " + DAYS + " days.");
                    info.setText("<html><b>" + Html.esc(t.label()) + "</b> · " + s.size() + " stories on " + perDay.size() + (perDay.size() == 1 ? " day" : " days")
                            + " · matched by " + Html.esc(t.names().isEmpty() ? "$" + t.symbol() : "$" + t.symbol() + ", " + String.join(", ", t.names())) + "</html>");
                } catch (Exception ex) { info.setText("Could not show the ticker: " + ex.getMessage()); }
            }
        }.execute();
    }

    static String dayLabel(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return DAY.format(d);
    }

    private void addTicker() {
        JTextField sym = new JTextField(8), names = new JTextField(30);
        sym.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void fill() {
                List<String> k = Tickers.knownNames(sym.getText().strip());
                if (!k.isEmpty() && names.getText().isBlank()) names.setText(String.join(", ", k));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { SwingUtilities.invokeLater(this::fill); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Symbol (e.g. NVDA, LMT, BA):")); p.add(sym);
        p.add(new JLabel("Company names to look for, separated by commas:")); p.add(names);
        p.add(new JLabel("<html><span style='color:" + Theme.muted() + "'>Well-known tickers fill in their names for you.</span></html>"));
        if (JOptionPane.showConfirmDialog(this, p, "Follow a ticker", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String s = sym.getText().strip().toUpperCase(Locale.ROOT).replace("$", "");
        if (!s.matches("[A-Z][A-Z0-9.\\-]{0,9}")) { JOptionPane.showMessageDialog(this, "Enter a ticker symbol such as NVDA."); return; }
        List<String> n = new ArrayList<>();
        for (String x : names.getText().split(",")) if (!x.isBlank()) n.add(x.strip());
        List<Tickers.Ticker> all = new ArrayList<>(host.service().tickers().all());
        all.removeIf(t -> t.symbol().equals(s));
        all.add(new Tickers.Ticker(s, n));
        save(all, s);
    }

    private void removeSelected() {
        Tickers.Ticker t = list.getSelectedValue();
        if (t == null) return;
        List<Tickers.Ticker> all = new ArrayList<>(host.service().tickers().all());
        all.removeIf(x -> x.symbol().equals(t.symbol()));
        save(all, null);
    }

    private void save(List<Tickers.Ticker> all, String select) {
        try {
            host.service().tickers().save(all);
            reload();
            if (select != null) for (int i = 0; i < model.size(); i++) if (model.get(i).symbol().equals(select)) list.setSelectedIndex(i);
            host.articlesChanged();      // Finance ties change
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save your tickers: " + ex.getMessage());
        }
    }

    void refreshPage() { browser.refreshDetail(); list.repaint(); }
}
