package com.linkeddesk.ui;

import com.linkeddesk.feeds.FeedCatalog;

import javax.swing.*;
import java.awt.*;

/** A plain-text editor for feeds.txt: one "Category | Name | URL" per line. */
final class FeedEditorDialog extends JDialog {
    FeedEditorDialog(Window owner) {
        super(owner, "Feed list", ModalityType.APPLICATION_MODAL);
        JTextArea text = new JTextArea(FeedCatalog.rawText(), 28, 110);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setCaretPosition(0);
        JLabel help = new JLabel("<html>One feed per line: <b>Category | Source name | Feed URL</b>. "
                + "Categories: World, Finance, Politics, Society, Tech. Lines starting with # are ignored.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        JButton reset = new JButton("Restore default list");
        save.addActionListener(e -> {
            int n = FeedCatalog.parse(text.getText()).size();
            if (n == 0) {
                JOptionPane.showMessageDialog(this, "No valid feed lines found. Nothing was saved.");
                return;
            }
            try {
                FeedCatalog.saveRawText(text.getText());
                JOptionPane.showMessageDialog(this, n + " feeds saved. They'll be used on the next download.");
                dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Could not save", JOptionPane.ERROR_MESSAGE);
            }
        });
        cancel.addActionListener(e -> dispose());
        reset.addActionListener(e -> { text.setText(FeedCatalog.DEFAULT_FEEDS); text.setCaretPosition(0); });

        JPanel buttons = new JPanel(new BorderLayout());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(cancel); right.add(save);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(reset);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        getContentPane().add(help, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }
}
