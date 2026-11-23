package com.linkeddesk.ui;

import com.linkeddesk.analysis.Analyzer;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools > Words behind links: which words create the most links in the stories shown. A word that links
 * lots of unrelated stories is too generic; add it to the stop words and those links go away.
 */
final class LinkWordsDialog extends JDialog {
    private final Host host;
    private final Model model = new Model();
    private final JLabel info = new JLabel(" ");

    LinkWordsDialog(Window owner, Host host) {
        super(owner, "Words behind links", ModalityType.MODELESS);
        this.host = host;
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        JButton stop = new JButton("Add selected words to stop words");
        stop.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) { JOptionPane.showMessageDialog(this, "Select one or more words first."); return; }
            List<String> words = new ArrayList<>();
            for (int r : rows) words.add(model.rows.get(table.convertRowIndexToModel(r)).term());
            int ok = JOptionPane.showConfirmDialog(this, "<html>Never link stories on <b>" + Html.esc(String.join(", ", words))
                    + "</b>?<br>The links these words created will go away. You can undo this in Tools &gt; Your corrections &gt; Word lists.</html>",
                    "Add stop words", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
            try {
                host.service().addStopWords(words);
                host.status("Added to stop words: " + String.join(", ", words) + ". Regrouping…");
                host.correctionsChanged();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
            }
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        info.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(stop);
        south.add(close);
        getContentPane().add(info, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(640, 560);
        setLocationRelativeTo(owner);
    }

    void show(Analyzer.Result r) {
        model.rows = r.linkTerms();
        model.fireTableDataChanged();
        long links = r.stories().stream().mapToLong(c -> c.links.size()).sum() / 2;
        info.setText("<html><span style='color:" + Theme.note() + "'>" + String.format("%,d", links) + " links among " + String.format("%,d", r.stories().size())
                + " stories in the current view. Each link is counted once for each of its top shared words. "
                + "Words already used by more than 5% of stories are ignored automatically.</span></html>");
    }

    private static final class Model extends AbstractTableModel {
        List<Analyzer.TermUse> rows = List.of();
        private static final String[] COLS = {"Word", "Links it helps create", "Stories using it"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Integer.class; }
        @Override public Object getValueAt(int r, int c) {
            Analyzer.TermUse t = rows.get(r);
            return c == 0 ? t.term() : c == 1 ? t.links() : t.stories();
        }
    }
}
