package com.linkeddesk.ui;

import com.linkeddesk.app.Logs;

import javax.swing.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Log viewer: every download in the last 30 days with its per-feed detail, and the error log. */
final class LogsDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a");

    LogsDialog(Window owner) {
        super(owner, "Logs", ModalityType.MODELESS);
        DefaultListModel<Logs.Run> model = new DefaultListModel<>();
        JList<Logs.Run> runs = new JList<>(model);
        JTextArea detail = new JTextArea();
        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        runs.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                Logs.Run r = (Logs.Run) v;
                boolean bad = r.summary().matches(".*, [1-9]\\d* feeds failed.*") || r.summary().startsWith("skipped");
                setText("<html><b>" + Html.esc(r.when().format(WHEN)) + "</b> · " + Html.esc(r.who()) + " · " + Html.esc(r.duration())
                        + "<br><span style='color:" + (s ? "#ffffff" : bad ? Theme.bad() : Theme.note()) + "'>" + Html.esc(r.summary()) + "</span></html>");
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()),
                        BorderFactory.createEmptyBorder(5, 8, 5, 8)));
                return this;
            }
        });
        runs.addListSelectionListener(e -> {
            Logs.Run r = runs.getSelectedValue();
            detail.setText(r == null ? "" : r.detail());
            detail.setCaretPosition(0);
        });
        try {
            List<Logs.Run> list = Logs.recentRuns(30);
            model.addAll(list);
            if (!list.isEmpty()) runs.setSelectedIndex(0);
            else detail.setText("No downloads in the last 30 days.");
        } catch (Exception ex) {
            detail.setText("Could not read the logs: " + ex.getMessage());
        }
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(runs), new JScrollPane(detail));
        split.setDividerLocation(470);

        JTextArea errors = new JTextArea(Logs.errorLogText());
        errors.setEditable(false);
        errors.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        errors.setCaretPosition(errors.getDocument().getLength());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Downloads (last 30 days: " + model.size() + ")", split);
        tabs.addTab("Errors", new JScrollPane(errors));

        JButton folder = new JButton("Open logs folder");
        folder.addActionListener(e -> { try { Desktop.getDesktop().open(Logs.dir().toFile()); } catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); } });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(folder); south.add(close);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(1100, 640);
        setLocationRelativeTo(owner);
    }
}
