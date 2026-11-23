package com.linkeddesk.ui;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedCatalog;
import com.linkeddesk.schedule.WindowsTask;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Settings: daily download time, Windows task, feed list, optional Claude API key. */
final class SettingsDialog extends JDialog {
    private final Settings settings;
    private final JTextField time = new JTextField(6);
    private final JCheckBox daily = new JCheckBox("Download automatically every day while the app is open");
    private final JCheckBox onStart = new JCheckBox("Download when the app starts if the news is more than 20 hours old");
    private final JPasswordField apiKey = new JPasswordField(32);
    private final JTextField model = new JTextField(20);
    private final JComboBox<String> theme = new JComboBox<>(new String[]{"Light", "Dark"});
    private final JComboBox<String> textSize = new JComboBox<>(new String[]{"90%", "100%", "115%", "130%", "150%", "175%"});
    private final JCheckBox briefing = new JCheckBox("Show the briefing when the app opens and after each daily download");
    private boolean lookChanged;
    private final JTextField budget = new JTextField(6);
    private final JCheckBox digest = new JCheckBox("After the daily download, Claude writes a short digest of how the day's events connect");
    private final JSpinner checkLinks = new JSpinner(new SpinnerNumberModel(10, 0, 50, 1));
    private final JTextField priceIn = new JTextField(5), priceOut = new JTextField(5);
    private boolean saved;

    SettingsDialog(Frame owner, Settings settings) {
        super(owner, "Settings", true);
        this.settings = settings;
        time.setText(settings.dailyTime().toString());
        daily.setSelected(settings.dailyEnabled());
        onStart.setSelected(settings.fetchOnStart());
        apiKey.setText(settings.apiKey());
        model.setText(settings.model());
        theme.setSelectedIndex("dark".equalsIgnoreCase(settings.theme()) ? 1 : 0);
        textSize.setSelectedItem(settings.textSize() + "%");
        if (textSize.getSelectedIndex() < 0) textSize.setSelectedItem("100%");
        briefing.setSelected(settings.showBriefing());
        budget.setText(String.format(java.util.Locale.ROOT, "%.2f", settings.claudeBudget()));
        digest.setSelected(settings.claudeDigest());
        checkLinks.setValue(settings.claudeCheckLinks());
        priceIn.setText(settings.priceIn() > 0 ? Double.toString(settings.priceIn()) : "");
        priceOut.setText(settings.priceOut() > 0 ? Double.toString(settings.priceOut()) : "");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 4, 4);
        g.gridwidth = 2;

        form.add(section("Daily download"), g); g.gridy++;
        g.gridwidth = 1;
        form.add(new JLabel("Time of day (24-hour, HH:MM):"), g); g.gridx = 1; form.add(time, g); g.gridx = 0; g.gridy++;
        g.gridwidth = 2;
        form.add(daily, g); g.gridy++;
        form.add(onStart, g); g.gridy++;

        JPanel task = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton install = new JButton("Run daily even when the app is closed");
        JButton remove = new JButton("Remove that daily task");
        install.addActionListener(e -> installTask());
        remove.addActionListener(e -> removeTask());
        JButton check = new JButton("Check daily task");
        check.addActionListener(e -> checkTask());
        install.setEnabled(WindowsTask.supported());
        remove.setEnabled(WindowsTask.supported());
        check.setEnabled(WindowsTask.supported());
        task.add(install); task.add(Box.createHorizontalStrut(8)); task.add(remove);
        task.add(Box.createHorizontalStrut(8)); task.add(check);
        form.add(task, g); g.gridy++;
        form.add(note("Creates a Windows Task Scheduler job that downloads at the time above, even with the window closed. "
                + "If the PC is off or asleep at that time, it runs as soon as the PC is back on and online."), g); g.gridy++;

        form.add(section("Reading"), g); g.gridy++;
        g.gridwidth = 1;
        form.add(new JLabel("Colours:"), g); g.gridx = 1; form.add(theme, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("Text size:"), g); g.gridx = 1; form.add(textSize, g); g.gridx = 0; g.gridy++;
        g.gridwidth = 2;
        form.add(briefing, g); g.gridy++;
        form.add(note("Text size adds to Windows' own display scaling (Settings > Display > Scale), which the app follows."), g); g.gridy++;

        form.add(section("News sources"), g); g.gridy++;
        JPanel feeds = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton editFeeds = new JButton("Edit feed list…");
        editFeeds.addActionListener(e -> new FeedEditorDialog(this).setVisible(true));
        JButton openFolder = new JButton("Open data folder");
        openFolder.addActionListener(e -> {
            try { Desktop.getDesktop().open(AppPaths.home().toFile()); } catch (Exception ex) { error(ex); }
        });
        feeds.add(editFeeds); feeds.add(Box.createHorizontalStrut(8)); feeds.add(openFolder);
        form.add(feeds, g); g.gridy++;
        form.add(note(FeedCatalog.load().size() + " feeds configured. Data is kept in " + AppPaths.home()), g); g.gridy++;

        form.add(section("Claude (optional)"), g); g.gridy++;
        g.gridwidth = 1;
        form.add(new JLabel("Anthropic API key:"), g); g.gridx = 1; form.add(apiKey, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("Model:"), g); g.gridx = 1; form.add(model, g); g.gridx = 0; g.gridy++;
        g.gridwidth = 2;
        form.add(note("Used for \"Ask Claude why\" on links, the daily digest, link checks and the Ask tab. Get a key at "
                + "console.anthropic.com; usage is billed to that account. The key is stored encrypted: on Windows with your Windows "
                + "account's protection (DPAPI), so only you, on this PC, can read it."), g); g.gridy++;
        g.gridwidth = 1;
        form.add(new JLabel("Monthly budget (US$):"), g); g.gridx = 1; form.add(budget, g); g.gridx = 0; g.gridy++;
        g.gridwidth = 2;
        form.add(digest, g); g.gridy++;
        JPanel chk = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        chk.add(new JLabel("After the daily download, Claude checks the day's top "));
        chk.add(checkLinks);
        chk.add(new JLabel(" new links (0 = off)"));
        form.add(chk, g); g.gridy++;
        JPanel prices = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        double[] list = com.linkeddesk.ai.Pricing.of(settings.model());
        prices.add(new JLabel("Your prices per million tokens (blank = list price $" + fmt(list[0]) + " in / $" + fmt(list[1]) + " out):  in $"));
        prices.add(priceIn);
        prices.add(new JLabel("  out $"));
        prices.add(priceOut);
        form.add(prices, g); g.gridy++;
        String used;
        try { used = new com.linkeddesk.ai.ClaudeService(settings, AppPaths.home()).summary(); }
        catch (Exception ex) { used = ""; }
        form.add(note(used + ". A request that could take the month over the budget isn't sent; you'll see why instead. "
                + "Answers are cached, so the same question is never paid for twice. Tools > Claude usage shows the details. "
                + "Typical costs with Claude Sonnet 5.5: a digest or a check of 10 links about 1-2 cents, a question about 1 cent."), g);

        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel); buttons.add(ok);

        JScrollPane scroll = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        // Never taller than the screen (the form scrolls instead), also at large text sizes.
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(getWidth() + 20, screen.width - 20), Math.min(getHeight(), screen.height - 20));
        setLocationRelativeTo(owner);
    }

    boolean wasSaved() { return saved; }

    private static String fmt(double d) { return d == Math.rint(d) ? String.format("%.0f", d) : String.format("%.2f", d); }

    /** True if the colours or text size changed (the window must be redrawn). */
    boolean lookChanged() { return lookChanged; }

    private LocalTime parsedTime() {
        try {
            return LocalTime.parse(time.getText().trim().length() == 4 ? "0" + time.getText().trim() : time.getText().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void save() {
        LocalTime t = parsedTime();
        if (t == null) {
            JOptionPane.showMessageDialog(this, "Enter the time as HH:MM, for example 06:30.", "Settings", JOptionPane.WARNING_MESSAGE);
            return;
        }
        settings.reload();
        settings.setDailyTime(t);
        settings.setDailyEnabled(daily.isSelected());
        settings.setFetchOnStart(onStart.isSelected());
        try {
            String typed = new String(apiKey.getPassword());
            if (!typed.equals(settings.apiKey())) settings.setApiKey(typed);
        } catch (IllegalStateException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        settings.setModel(model.getText());
        String th = theme.getSelectedIndex() == 1 ? "dark" : "light";
        int size = Integer.parseInt(((String) textSize.getSelectedItem()).replace("%", ""));
        lookChanged = !th.equalsIgnoreCase(settings.theme()) || size != settings.textSize();
        settings.setTheme(th);
        settings.setTextSize(size);
        settings.setShowBriefing(briefing.isSelected());
        try {
            double b = Double.parseDouble(budget.getText().trim().replace("$", ""));
            double pi = priceIn.getText().isBlank() ? 0 : Double.parseDouble(priceIn.getText().trim().replace("$", ""));
            double po = priceOut.getText().isBlank() ? 0 : Double.parseDouble(priceOut.getText().trim().replace("$", ""));
            if (b < 0 || pi < 0 || po < 0) throw new NumberFormatException();
            settings.setClaudeBudget(b);
            settings.setPrices(pi, po);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter the budget and prices as numbers, for example 5.00.", "Settings", JOptionPane.WARNING_MESSAGE);
            return;
        }
        settings.setClaudeDigest(digest.isSelected());
        settings.setClaudeCheckLinks((Integer) checkLinks.getValue());
        settings.save();
        saved = true;
        dispose();
    }

    private void installTask() {
        LocalTime t = parsedTime();
        if (t == null) { JOptionPane.showMessageDialog(this, "Enter a valid time first (HH:MM)."); return; }
        try {
            WindowsTask.install(t);
            JOptionPane.showMessageDialog(this, "Done. Windows will download the news every day at " + t
                    + ".\nYou can see it in Task Scheduler as \"" + WindowsTask.TASK_NAME + "\".");
        } catch (Exception ex) { error(ex); }
    }

    private void removeTask() {
        try {
            WindowsTask.remove();
            JOptionPane.showMessageDialog(this, "The daily Windows task was removed.");
        } catch (Exception ex) { error(ex); }
    }

    private void checkTask() {
        try {
            JTextArea area = new JTextArea(WindowsTask.query(), 22, 80);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(area),
                    "Daily task status (Last Result 0 means the last run succeeded)", JOptionPane.PLAIN_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "The daily task isn't set up yet.\n\n" + ex.getMessage(),
                    "Daily task", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void error(Exception ex) {
        JOptionPane.showMessageDialog(this, ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
    }

    private static JLabel section(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 2f));
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return l;
    }

    private static JLabel note(String s) {
        JLabel l = new JLabel("<html><div style='width:430px;color:" + Theme.muted() + "'>" + Html.esc(s) + "</div></html>");
        return l;
    }
}
