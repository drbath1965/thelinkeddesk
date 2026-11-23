package com.linkeddesk.ui;

import com.linkeddesk.alerts.Watches;
import com.linkeddesk.analysis.SearchQuery;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Add or edit a watched subject: a name, the words to look for, and whether to notify. */
final class WatchEditor extends JDialog {
    private Watches.Watch result;

    WatchEditor(Window owner, Watches.Watch w, String words, List<Watches.Watch> others) {
        super(owner, w == null ? "Watch a subject" : "Edit watch", ModalityType.APPLICATION_MODAL);
        JTextField name = new JTextField(w == null ? suggestName(words) : w.name(), 28);
        JTextField text = new JTextField(words, 36);
        JCheckBox notify = new JCheckBox("Notify me when new articles match", w == null || w.alert());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 4);
        g.gridx = 0; g.gridy = 0; form.add(new JLabel("Name:"), g);
        g.gridx = 1; form.add(name, g);
        g.gridx = 0; g.gridy++; form.add(new JLabel("Look for:"), g);
        g.gridx = 1; form.add(text, g);
        g.gridx = 1; g.gridy++;
        form.add(new JLabel("<html><span style='color:" + Theme.muted() + "'>e.g. <b>\"strait of hormuz\" OR hormuz</b> · <b>Fed</b> (also finds Federal Reserve) "
                + "· <b>hypersonic -game</b> · <b>Redstone source:al.com</b></span></html>"), g);
        g.gridy++; form.add(notify, g);
        JButton ok = new JButton("Save"), cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String n = name.getText().strip(), t = text.getText().strip();
            if (n.isEmpty() || t.isEmpty()) { JOptionPane.showMessageDialog(this, "Enter a name and the words to look for."); return; }
            if (others.stream().anyMatch(x -> x.name().equalsIgnoreCase(n))) { JOptionPane.showMessageDialog(this, "There is already a watch called \"" + n + "\"."); return; }
            SearchQuery q = new SearchQuery(t);
            if (!q.problems().isEmpty()) { JOptionPane.showMessageDialog(this, String.join("\n", q.problems())); return; }
            if (!q.hasWords()) { JOptionPane.showMessageDialog(this, "Add at least one word to look for (filters alone match too much)."); return; }
            result = new Watches.Watch(n, t, notify.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(cancel); b.add(ok);
        getRootPane().setDefaultButton(ok);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(b, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    static String suggestName(String words) {
        String n = words.replace("\"", "").replaceAll("\\s+OR\\s+.*", "").replaceAll("\\s+-\\S+", "").strip();
        if (n.isEmpty()) return "";
        n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        return n.length() > 32 ? n.substring(0, 32).strip() : n;
    }

    Watches.Watch result() { return result; }
}
