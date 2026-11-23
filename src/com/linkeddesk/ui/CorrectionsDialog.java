package com.linkeddesk.ui;

import com.linkeddesk.analysis.DeskRules;
import com.linkeddesk.analysis.Feedback;
import com.linkeddesk.analysis.Lexicon;
import com.linkeddesk.app.AppPaths;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools > Your corrections: the link verdicts and desk rules you've given (each can be removed), and the
 * word lists (aliases and stop words) as editable text.
 */
final class CorrectionsDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private final Host host;
    private final VerdictModel verdicts = new VerdictModel();
    private final DefaultListModel<DeskRules.Rule> rules = new DefaultListModel<>();

    CorrectionsDialog(Window owner, Host host) {
        super(owner, "Your corrections", ModalityType.MODELESS);
        this.host = host;
        JTabbedPane tabs = new JTabbedPane();

        // ---- link verdicts
        JTable table = new JTable(verdicts);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(330);
        table.getColumnModel().getColumn(2).setPreferredWidth(330);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        table.getColumnModel().getColumn(4).setPreferredWidth(110);
        JButton removeVerdict = new JButton("Remove verdict");
        removeVerdict.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            try {
                for (int r : rows) host.service().removeVerdict(verdicts.rows.get(table.convertRowIndexToModel(r)));
                reload();
                host.correctionsChanged();
            } catch (Exception ex) { error(ex); }
        });
        JPanel vp = new JPanel(new BorderLayout());
        vp.add(note("Links you marked right are always shown; links you marked wrong are hidden, and other links resting on the same "
                + "two words count half. Articles you took out of a story stay apart from it. All of them also train Tools > Calibrate linking."), BorderLayout.NORTH);
        vp.add(new JScrollPane(table), BorderLayout.CENTER);
        vp.add(row(removeVerdict), BorderLayout.SOUTH);
        tabs.addTab("Link verdicts", vp);

        // ---- desk rules
        JList<DeskRules.Rule> list = new JList<>(rules);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                setText(((DeskRules.Rule) v).describe());
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                return this;
            }
        });
        JButton removeRule = new JButton("Remove rule");
        removeRule.addActionListener(e -> {
            try {
                for (DeskRules.Rule r : list.getSelectedValuesList()) host.service().removeDeskRule(r);
                reload();
                host.correctionsChanged();
            } catch (Exception ex) { error(ex); }
        });
        JPanel rp = new JPanel(new BorderLayout());
        rp.add(note("Made with \"Move to another desk…\" on a story. A story rule beats a headline-word rule, which beats an outlet rule."), BorderLayout.NORTH);
        rp.add(new JScrollPane(list), BorderLayout.CENTER);
        rp.add(row(removeRule), BorderLayout.SOUTH);
        tabs.addTab("Desk rules", rp);

        // ---- word lists
        JPanel wp = new JPanel(new BorderLayout());
        JTabbedPane words = new JTabbedPane(JTabbedPane.BOTTOM);
        words.addTab("Aliases (aliases.txt)", fileEditor(AppPaths.aliasesFile(), Lexicon.DEFAULT_ALIASES));
        words.addTab("Stop words (stopwords.txt)", fileEditor(AppPaths.stopwordsFile(), Lexicon.DEFAULT_STOPWORDS));
        wp.add(note("Aliases make different names count as the same thing (\"Fed\" = \"Federal Reserve\"). Stop words never link stories. "
                + "Changes apply as soon as you save."), BorderLayout.NORTH);
        wp.add(words, BorderLayout.CENTER);
        tabs.addTab("Word lists", wp);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(row(close), BorderLayout.SOUTH);
        setSize(1120, 620);
        setLocationRelativeTo(owner);
        reload();
    }

    void reload() {
        Feedback fb = host.service().options().feedback();
        verdicts.rows = new ArrayList<>(fb.entries());
        verdicts.rows.sort((a, b) -> Long.compare(b.time(), a.time()));
        verdicts.fireTableDataChanged();
        rules.clear();
        rules.addAll(host.service().options().desks().rules());
    }

    private JComponent fileEditor(Path file, String defaults) {
        JTextArea text = new JTextArea(20, 100);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        try {
            host.service().options(); // creates the files with their defaults if missing
            text.setText(Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : defaults);
        } catch (Exception ex) { text.setText(defaults); }
        text.setCaretPosition(0);
        JButton save = new JButton("Save");
        JButton reset = new JButton("Restore defaults");
        save.addActionListener(e -> {
            try {
                Files.writeString(file, text.getText(), StandardCharsets.UTF_8);
                List<String> problems = Lexicon.parse(file.equals(AppPaths.aliasesFile()) ? text.getText() : "", "").problems();
                host.status(file.getFileName() + " saved." + (problems.isEmpty() ? "" : " " + problems.size() + " line(s) skipped: " + problems.get(0)));
                host.correctionsChanged();
            } catch (Exception ex) { error(ex); }
        });
        reset.addActionListener(e -> { text.setText(defaults); text.setCaretPosition(0); });
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        JPanel b = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT)); left.add(reset);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT)); right.add(save);
        b.add(left, BorderLayout.WEST);
        b.add(right, BorderLayout.EAST);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    private static JComponent note(String text) {
        JLabel l = new JLabel("<html><span style='color:" + Theme.note() + "'>" + Html.esc(text) + "</span></html>");
        l.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return l;
    }

    private static JPanel row(JComponent... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        for (JComponent c : cs) p.add(c);
        return p;
    }

    private void error(Exception ex) {
        JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "The Linked Desk", JOptionPane.ERROR_MESSAGE);
    }

    private static final class VerdictModel extends AbstractTableModel {
        List<Feedback.Entry> rows = new ArrayList<>();
        private static final String[] COLS = {"Verdict", "Story", "Other story", "Shared words", "When"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Feedback.Entry e = rows.get(r);
            return switch (c) {
                case 0 -> switch (e.kind()) { case RIGHT -> "✓ Link right"; case WRONG -> "✗ Link wrong"; case SPLIT -> "Not this story"; };
                case 1 -> e.headlineA();
                case 2 -> e.kind() == Feedback.Kind.SPLIT ? "taken out of: " + e.headlineB() : e.headlineB();
                case 3 -> String.join(", ", e.terms());
                default -> WHEN.format(Instant.ofEpochMilli(e.time()));
            };
        }
    }
}
