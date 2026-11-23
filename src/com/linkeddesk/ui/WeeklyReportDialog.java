package com.linkeddesk.ui;

import com.linkeddesk.export.Doc;
import com.linkeddesk.export.Exporter;
import com.linkeddesk.export.WeeklyReport;
import com.linkeddesk.store.ArticleStore;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Tools > Weekly report (EXP-F02): pick any week in the archive, preview the report, save it as Word, HTML or Markdown. */
final class WeeklyReportDialog extends JDialog {
    private final Host host;
    private final DefaultListModel<LocalDate> weeks = new DefaultListModel<>();
    private final JList<LocalDate> list = new JList<>(weeks);
    private final JEditorPane preview = StoryBrowser.htmlPane();
    private final JButton word = new JButton("Save as Word…"), other = new JButton("Save as HTML or Markdown…"), copy = new JButton("Copy");
    private final JLabel status = new JLabel(" ");
    private Doc doc;
    private int run;

    WeeklyReportDialog(Frame owner, Host host) {
        super(owner, "Weekly report", ModalityType.MODELESS);
        this.host = host;
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                LocalDate mon = (LocalDate) v;
                setText(i == 0 ? "This week (so far)" : i == 1 ? "Last week · " + WeeklyReport.label(mon) : WeeklyReport.label(mon));
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) make(list.getSelectedValue()); });
        preview.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null)
                try { Desktop.getDesktop().browse(URI.create(e.getURL().toString())); } catch (Exception ignored) { }
        });
        word.addActionListener(e -> { if (doc != null) ExportUi.save(this, host, doc, List.of(), Exporter.Format.WORD); });
        other.addActionListener(e -> { if (doc != null) ExportUi.save(this, host, doc, List.of(), Exporter.Format.HTML, Exporter.Format.MARKDOWN); });
        copy.addActionListener(e -> { if (doc != null) { ExportUi.copy(doc); status.setText("Copied. Paste into Word, Outlook or Gmail; the links come along."); } });
        JPanel left = new JPanel(new BorderLayout());
        JLabel pick = new JLabel("Week (Monday to Sunday):");
        pick.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        left.add(pick, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(word); buttons.add(other); buttons.add(copy); buttons.add(status);
        JPanel right = new JPanel(new BorderLayout());
        right.add(new JScrollPane(preview), BorderLayout.CENTER);
        right.add(buttons, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(270);
        getContentPane().add(split);
        for (JButton b : List.of(word, other, copy)) b.setEnabled(false);
        fillWeeks();
        setSize(Math.min(1150, owner.getWidth()), Math.min(820, owner.getHeight()));
        setLocationRelativeTo(owner);
        if (weeks.size() > 1) list.setSelectedIndex(1); else if (!weeks.isEmpty()) list.setSelectedIndex(0);
    }

    private void fillWeeks() {
        LocalDate first = LocalDate.now().minusWeeks(12);
        try {
            List<Path> files = host.service().store().files();
            if (!files.isEmpty()) { YearMonth m = ArticleStore.monthOf(files.get(0)); if (m.getYear() > 1970) first = m.atDay(1); }
        } catch (Exception ignored) { }
        for (LocalDate mon = WeeklyReport.weekStart(LocalDate.now()); !mon.plusDays(6).isBefore(first); mon = mon.minusWeeks(1)) weeks.addElement(mon);
    }

    /** Re-renders the preview (after the news zoom changed). */
    void refreshPage() { if (list.getSelectedValue() != null) make(list.getSelectedValue()); }

    private void make(LocalDate mon) {
        int my = ++run;
        for (JButton b : List.of(word, other, copy)) b.setEnabled(false);
        status.setText("Making the report for " + WeeklyReport.label(mon) + "…");
        preview.setText(Html.empty("Making the report…"));
        new SwingWorker<Doc, Void>() {
            @Override protected Doc doInBackground() { return host.service().weeklyReport(mon); }
            @Override protected void done() {
                if (my != run) return;
                try {
                    doc = get();
                    preview.setText("<html><head><style>" + Html.css() + " p{margin:4px 0} ul{margin:2px 0 6px 18px} li{margin:2px 0} .m{color:" + Theme.muted() + "} .s{font-size:"
                            + Theme.pt(9) + "}</style></head><body>" + doc.htmlBody() + "</body></html>");
                    preview.setCaretPosition(0);
                    for (JButton b : List.of(word, other, copy)) b.setEnabled(true);
                    status.setText(" ");
                } catch (Exception ex) {
                    status.setText("Could not make the report: " + ex.getMessage());
                }
            }
        }.execute();
    }
}
