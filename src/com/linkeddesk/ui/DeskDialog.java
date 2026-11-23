package com.linkeddesk.ui;

import com.linkeddesk.analysis.DeskRules;
import com.linkeddesk.analysis.Feedback;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * "Move to another desk": puts a story in a desk (and optionally a second one), for this story only,
 * for everything from one outlet, or for every headline containing a word.
 */
final class DeskDialog extends JDialog {
    private DeskRules.Rule rule;

    DeskDialog(Window owner, StoryCluster c) {
        super(owner, "Move to another desk", ModalityType.APPLICATION_MODAL);
        JComboBox<Category> desk = new JComboBox<>(Category.values());
        desk.setSelectedItem(c.category);
        JComboBox<Object> also = new JComboBox<>();
        also.addItem("No second desk");
        for (Category x : Category.values()) also.addItem(x);
        if (c.also != null) also.setSelectedItem(c.also);
        ListCellRenderer<Object> labels = new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof Category x ? x.label() : v, i, s, f);
            }
        };
        desk.setRenderer(labels);
        also.setRenderer(labels);

        JRadioButton story = new JRadioButton("This story only (it stays there after new downloads)", true);
        JRadioButton source = new JRadioButton("Everything from");
        JRadioButton keyword = new JRadioButton("Every headline containing");
        ButtonGroup g = new ButtonGroup();
        g.add(story); g.add(source); g.add(keyword);
        JComboBox<String> sources = new JComboBox<>(c.sources().toArray(String[]::new));
        String suggest = c.keyTerms.isEmpty() ? "" : c.keyTerms.get(0);
        JTextField word = new JTextField(suggest, 18);
        sources.addActionListener(e -> source.setSelected(true));
        word.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { keyword.setSelected(true); }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 4, 4, 4);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        JLabel head = new JLabel("<html><b>" + Html.esc(c.headline) + "</b><br><span style='color:" + Theme.muted() + "'>Now in "
                + c.category.label() + (c.also != null ? " (also " + c.also.label() + ")" : "") + "</span></html>");
        head.setPreferredSize(new Dimension(520, head.getPreferredSize().height + 20));
        form.add(head, gc);
        gc.gridwidth = 1; gc.gridy++;
        form.add(new JLabel("Put it in:"), gc); gc.gridx = 1; form.add(desk, gc);
        gc.gridx = 0; gc.gridy++;
        form.add(new JLabel("Also show in:"), gc); gc.gridx = 1; form.add(also, gc);
        gc.gridx = 0; gc.gridy++; gc.gridwidth = 2;
        form.add(new JLabel("Apply to:"), gc);
        gc.gridy++; form.add(story, gc);
        gc.gridy++; gc.gridwidth = 1; form.add(source, gc); gc.gridx = 1; form.add(sources, gc);
        gc.gridx = 0; gc.gridy++; form.add(keyword, gc); gc.gridx = 1; form.add(word, gc);
        gc.gridx = 0; gc.gridy++; gc.gridwidth = 2;
        form.add(new JLabel("<html><span style='color:" + Theme.muted() + "'>Rules are listed in Tools &gt; Your corrections, where you can remove them.</span></html>"), gc);

        JButton ok = new JButton("Move");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            Category d = (Category) desk.getSelectedItem();
            Category second = also.getSelectedItem() instanceof Category x && x != d ? x : null;
            if (keyword.isSelected()) {
                String w = word.getText().trim();
                if (w.isEmpty()) { JOptionPane.showMessageDialog(this, "Type the word or phrase to look for in headlines."); return; }
                rule = new DeskRules.Rule(DeskRules.Kind.KEYWORD, w, d, second, w);
            } else if (source.isSelected()) {
                String s = (String) sources.getSelectedItem();
                rule = new DeskRules.Rule(DeskRules.Kind.SOURCE, s, d, second, s);
            } else {
                rule = new DeskRules.Rule(DeskRules.Kind.STORY, String.join(",", Feedback.ids(c)), d, second, c.headline);
            }
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /** The rule chosen, or null if cancelled. */
    DeskRules.Rule rule() { return rule; }

    static List<Category> desks() { return List.of(Category.values()); }
}
