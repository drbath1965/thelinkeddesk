package com.linkeddesk.ui;

import com.linkeddesk.alerts.Sources;
import com.linkeddesk.model.Article;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Tools > Sources (ALR-F03): every outlet in the last 30 days with its article count. Mute one to hide it from your
 * lists (it stays in the archive), or give it a weight so it counts more or less toward "most covered".
 */
final class SourcesDialog extends JDialog {
    private final Host host;
    private final List<Object[]> rows = new ArrayList<>();   // outlet, count, muted, weight
    private final AbstractTableModel model = new AbstractTableModel() {
        final String[] cols = {"Outlet", "Articles (30 days)", "Muted", "Weight"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 4; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c == 1 ? Integer.class : c == 2 ? Boolean.class : c == 3 ? Double.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c >= 2; }
        public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        public void setValueAt(Object v, int r, int c) {
            Object[] row = rows.get(r);
            if (c == 3) {
                double w = v instanceof Number n ? n.doubleValue() : 1;
                if (w < 0.25 || w > 5) { Toolkit.getDefaultToolkit().beep(); host.status("Weights go from 0.25 to 5."); return; }
                row[3] = w;
            } else row[c] = v;
            apply(row);
            fireTableRowsUpdated(r, r);
        }
    };
    private final JTable table = new JTable(model);
    private final JTextField find = new JTextField(16);
    private boolean changed;

    SourcesDialog(Frame owner, Host host) {
        super(owner, "Sources", ModalityType.APPLICATION_MODAL);
        this.host = host;
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Math.round(table.getRowHeight() * 1.25f));
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        JLabel intro = new JLabel("<html><div style='width:" + Html.cssPx(560) + "px'>Mute an outlet to hide its articles from Stories, the Briefing, "
                + "Search and Ask. Muted articles stay in your archive and come back if you unmute. A weight above 1 makes an outlet count more toward "
                + "\"Most covered\" and the briefing; below 1, less.</div></html>");
        intro.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(intro, BorderLayout.CENTER);
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT));
        f.add(new JLabel("Find:")); f.add(find);
        top.add(f, BorderLayout.SOUTH);
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() {
                @SuppressWarnings("unchecked") var sorter = (javax.swing.table.TableRowSorter<AbstractTableModel>) table.getRowSorter();
                String t = find.getText().strip();
                sorter.setRowFilter(t.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(t), 0));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(close);
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(b, BorderLayout.SOUTH);
        load();
        setSize(Math.min(760, owner.getWidth()), Math.min(620, owner.getHeight()));
        setLocationRelativeTo(owner);
    }

    private void load() {
        Sources src = host.service().sources().load();
        List<Article> recent = host.service().store().since(System.currentTimeMillis() - 30L * 86_400_000L);
        Map<String, Integer> counts = Sources.outlets(recent);
        rows.clear();
        Set<String> seen = new HashSet<>();
        // Outlets with your rules first, then the rest by article count.
        for (Sources.Rule r : src.rules()) {
            rows.add(new Object[]{r.name(), counts.getOrDefault(r.name(), 0), r.mute(), r.mute() ? 1.0 : r.weight()});
            seen.add(r.name());
        }
        for (var e : counts.entrySet()) if (seen.add(e.getKey())) rows.add(new Object[]{e.getKey(), e.getValue(), src.muted(e.getKey()), src.weight(e.getKey())});
        model.fireTableDataChanged();
    }

    private void apply(Object[] row) {
        try {
            host.service().sources().set((String) row[0], (Boolean) row[2], (Double) row[3]);
            changed = true;
            host.status(((Boolean) row[2] ? "Muted " : "Saved ") + row[0] + ".");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    boolean changed() { return changed; }
}
