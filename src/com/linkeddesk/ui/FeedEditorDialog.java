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
        JButton addMedia = new JButton("Add a podcast, YouTube channel or social account…");
        addMedia.addActionListener(e -> addMedia(text));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(reset);
        left.add(addMedia);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        getContentPane().add(help, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Asks for a link (YouTube, Apple Podcasts, Mastodon, Bluesky, Reddit or a feed), finds its feed, adds a line (FET-F06). */
    private void addMedia(JTextArea text) {
        JTextField link = new JTextField(40), name = new JTextField(24);
        JComboBox<com.linkeddesk.model.Category> desk = new JComboBox<>(com.linkeddesk.model.Category.values());
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("<html>Paste the link from your browser, for example:<br>"
                + "youtube.com/@channel · podcasts.apple.com/…/id123 · mastodon.social/@account · bsky.app/profile/name · reddit.com/r/worldnews</html>"));
        p.add(link);
        p.add(new JLabel("Name to show (optional):")); p.add(name);
        p.add(new JLabel("Desk:")); p.add(desk);
        if (JOptionPane.showConfirmDialog(this, p, "Add a source", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<com.linkeddesk.feeds.FeedLinks.Result, Void>() {
            @Override protected com.linkeddesk.feeds.FeedLinks.Result doInBackground() throws Exception {
                return com.linkeddesk.feeds.FeedLinks.resolve(link.getText(), com.linkeddesk.feeds.FeedLinks.WEB);
            }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    var r = get();
                    String n = !name.getText().isBlank() ? name.getText().strip() : !r.name().isBlank() ? r.name() : java.net.URI.create(r.feedUrl()).getHost();
                    String kind = switch (r.kind()) { case "video" -> "YouTube"; case "audio" -> "podcast"; case "post" -> "social"; default -> "feed"; };
                    String line = ((com.linkeddesk.model.Category) desk.getSelectedItem()).name().charAt(0)
                            + ((com.linkeddesk.model.Category) desk.getSelectedItem()).name().substring(1).toLowerCase() + " | " + n.replace("|", "/") + " | " + r.feedUrl();
                    text.append((text.getText().endsWith("\n") ? "" : "\n") + line + "\n");
                    JOptionPane.showMessageDialog(FeedEditorDialog.this, "Added (" + kind + "):\n" + line + "\n\nClick Save, then News > Test all feeds to check it.");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(FeedEditorDialog.this, c.getMessage(), "Add a source", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
