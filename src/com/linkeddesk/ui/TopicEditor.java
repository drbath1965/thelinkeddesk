package com.linkeddesk.ui;

import com.linkeddesk.feeds.Topic;
import com.linkeddesk.feeds.TopicCatalog;
import com.linkeddesk.model.Category;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Add or edit a custom topic. */
final class TopicEditor extends JDialog {
    private static final String[] REGIONS = {"en-US", "en-GB", "en-CA", "en-AU", "en-IN", "en-ZA", "en-SG", "fr-FR", "de-DE", "es-ES", "es-419", "pt-BR", "ja-JP", "ar-AE"};
    private final JTextField name = new JTextField(28);
    private final JTextField query = new JTextField(36);
    private final JComboBox<Category> desk = new JComboBox<>(Category.values());
    private final JComboBox<String> region = new JComboBox<>(REGIONS);
    private final JCheckBox paused = new JCheckBox("Paused (keep the topic but don't download it)");
    private final JLabel error = new JLabel(" ");
    private Topic result;

    TopicEditor(Window owner, Topic existing, List<Topic> all, boolean isNew) {
        super(owner, isNew ? "Add topic" : "Edit topic", ModalityType.APPLICATION_MODAL);
        if (existing != null) {
            name.setText(existing.name());
            query.setText(existing.query());
            desk.setSelectedItem(existing.desk());
            region.setEditable(true);
            region.setSelectedItem(existing.region());
            paused.setSelected(existing.paused());
        }
        region.setEditable(true);
        List<Topic> others = all.stream().filter(t -> isNew || existing == null || !t.name().equalsIgnoreCase(existing.name())).toList();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 4, 4); g.gridx = 0; g.gridy = 0;
        form.add(new JLabel("Topic name:"), g); g.gridx = 1; form.add(name, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("Search words:"), g); g.gridx = 1; form.add(query, g); g.gridx = 0; g.gridy++;
        g.gridx = 1;
        form.add(new JLabel("<html><span style='color:" + Theme.muted() + "'>e.g. <b>hypersonic OR \"glide vehicle\" -game</b><br>"
                + "\"exact phrase\" · OR between alternatives · -word to leave out</span></html>"), g);
        g.gridx = 0; g.gridy++;
        form.add(new JLabel("Desk:"), g); g.gridx = 1; form.add(desk, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("Language-region:"), g); g.gridx = 1; form.add(region, g); g.gridx = 0; g.gridy++;
        g.gridx = 1; form.add(paused, g); g.gridy++;
        error.setForeground(new Color(0xB42318));
        form.add(error, g);

        JButton ok = new JButton(isNew ? "Add topic" : "Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String msg = TopicCatalog.validate(name.getText(), query.getText(), others);
            if (msg != null) { error.setText(msg); return; }
            result = new Topic((Category) desk.getSelectedItem(), name.getText().trim(), query.getText().trim(),
                    String.valueOf(region.getSelectedItem()).trim(), paused.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel); buttons.add(ok);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(owner);
    }

    /** The topic as entered, or null if cancelled. */
    Topic result() { return result; }
}
