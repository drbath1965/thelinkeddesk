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
    // Alerts (P5)
    private final JCheckBox notifications = new JCheckBox("Show a Windows notification when new articles match your watchlist");
    private final JCheckBox notifyLinks = new JCheckBox("Also notify about new links between desks");
    private final JCheckBox quiet = new JCheckBox("Quiet hours, no pop-ups from");
    private final JTextField quietFrom = new JTextField(5), quietTo = new JTextField(5);
    private final JCheckBox email = new JCheckBox("Email me the briefing once a day, after the daily download");
    private final JTextField smtpHost = new JTextField(20), smtpUser = new JTextField(22), emailFrom = new JTextField(22), emailTo = new JTextField(30);
    private final JTextField smtpPort = new JTextField(5);
    private final JComboBox<String> smtpSecurity = new JComboBox<>(new String[]{"STARTTLS", "SSL", "NONE"});
    private final JPasswordField smtpPassword = new JPasswordField(22);
    // Other languages, full text, phone (ANL-F11, FET-F07, UI-F12)
    private final JCheckBox translate = new JCheckBox("Translate headlines of articles in other languages with Claude (after the daily download)");
    private final JSpinner translatePerDay = new JSpinner(new SpinnerNumberModel(150, 0, 2000, 10));
    private final JCheckBox keepFull = new JCheckBox("Keep the full text when a feed includes it");
    private final JCheckBox searchFull = new JCheckBox("Search inside full texts too");
    private final JTextField phoneFolder = new JTextField(30);
    /** Writes the phone page now (set by the main window); returns where. */
    java.util.concurrent.Callable<String> writePhone;
    /** Sends the briefing now with the email settings as saved (set by the main window); returns what happened. */
    java.util.concurrent.Callable<String> testEmail;

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

        notifications.setSelected(settings.notifications());
        notifyLinks.setSelected(settings.notifyLinks());
        quiet.setSelected(settings.quietFrom() != null);
        quietFrom.setText(settings.quietFrom() == null ? "22:00" : settings.quietFrom().toString());
        quietTo.setText(settings.quietTo() == null ? "07:00" : settings.quietTo().toString());
        email.setSelected(settings.emailEnabled());
        smtpHost.setText(settings.smtpHost());
        smtpPort.setText(Integer.toString(settings.smtpPort()));
        smtpSecurity.setSelectedItem(settings.smtpSecurity().toUpperCase(java.util.Locale.ROOT));
        smtpUser.setText(settings.smtpUser());
        smtpPassword.setText(settings.smtpPassword());
        emailFrom.setText(settings.emailFrom());
        emailTo.setText(settings.emailTo());
        smtpSecurity.addActionListener(e -> {
            String p = smtpPort.getText().trim();
            if (p.equals("587") || p.equals("465") || p.equals("25") || p.isEmpty())
                smtpPort.setText(switch ((String) smtpSecurity.getSelectedItem()) { case "SSL" -> "465"; case "NONE" -> "25"; default -> "587"; });
        });

        translate.setSelected(settings.translate());
        translatePerDay.setValue(settings.translatePerDay());
        keepFull.setSelected(settings.keepFullText());
        searchFull.setSelected(settings.searchFullText());
        phoneFolder.setText(settings.phoneFolder());

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

        form.add(section("Notifications"), g); g.gridy++;
        form.add(notifications, g); g.gridy++;
        form.add(notifyLinks, g); g.gridy++;
        JPanel q = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        q.add(quiet); q.add(quietFrom); q.add(new JLabel("  to  ")); q.add(quietTo);
        form.add(q, g); g.gridy++;
        form.add(note("One notification per download, grouped by watch. During quiet hours nothing pops up; what arrived is "
                + "shown together once quiet hours end. Notifications also come from the daily Windows task when the app is closed."), g); g.gridy++;

        form.add(section("Email digest"), g); g.gridy++;
        form.add(email, g); g.gridy++;
        g.gridwidth = 1;
        form.add(new JLabel("Mail server (SMTP):"), g); g.gridx = 1;
        JPanel hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        hostRow.add(smtpHost); hostRow.add(new JLabel("  port ")); hostRow.add(smtpPort); hostRow.add(new JLabel("  ")); hostRow.add(smtpSecurity);
        form.add(hostRow, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("User name:"), g); g.gridx = 1; form.add(smtpUser, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("Password:"), g); g.gridx = 1; form.add(smtpPassword, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("From:"), g); g.gridx = 1; form.add(emailFrom, g); g.gridx = 0; g.gridy++;
        form.add(new JLabel("To (commas between):"), g); g.gridx = 1; form.add(emailTo, g); g.gridx = 0; g.gridy++;
        g.gridwidth = 2;
        JButton test = new JButton("Send test email now");
        test.addActionListener(e -> sendTest(test));
        form.add(test, g); g.gridy++;
        form.add(note("Gmail: smtp.gmail.com, port 587, STARTTLS, your Gmail address as user name and an app password "
                + "(Google Account > Security > App passwords), not your normal password. Outlook.com: smtp-mail.outlook.com, 587, STARTTLS. "
                + "The password is stored encrypted like the API key."), g); g.gridy++;

        form.add(section("Phone"), g); g.gridy++;
        JPanel ph = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton choosePhone = new JButton("Choose…"), writeNow = new JButton("Write it now");
        choosePhone.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(phoneFolder.getText().isBlank() ? new java.io.File(System.getProperty("user.home")) : new java.io.File(phoneFolder.getText()));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Folder your phone can see (OneDrive, Dropbox, iCloud Drive…)");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) phoneFolder.setText(fc.getSelectedFile().getAbsolutePath());
        });
        writeNow.addActionListener(e -> {
            if (writePhone == null) return;
            if (phoneFolder.getText().isBlank()) { JOptionPane.showMessageDialog(this, "Choose a folder first."); return; }
            settings.reload(); settings.setPhoneFolder(phoneFolder.getText()); settings.save();
            writeNow.setEnabled(false);
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return writePhone.call(); }
                @Override protected void done() {
                    writeNow.setEnabled(true);
                    try { JOptionPane.showMessageDialog(SettingsDialog.this, "Written:\n" + get() + "\n\nOpen it on your phone in the OneDrive / Dropbox / Files app."); }
                    catch (Exception ex) { JOptionPane.showMessageDialog(SettingsDialog.this, "Couldn't write it: " + (ex.getCause() == null ? ex : ex.getCause()).getMessage()); }
                }
            }.execute();
        });
        ph.add(new JLabel("Folder: ")); ph.add(phoneFolder); ph.add(Box.createHorizontalStrut(6)); ph.add(choosePhone); ph.add(Box.createHorizontalStrut(6)); ph.add(writeNow);
        form.add(ph, g); g.gridy++;
        form.add(note("After each daily download the briefing is written as a web page made for a phone (\"The Linked Desk briefing.html\", "
                + "plus the last two weeks in a briefings folder). Pick a folder your phone syncs, such as one in OneDrive, and open the page "
                + "there. Nothing is put online. Leave the folder empty to switch this off."), g); g.gridy++;

        form.add(section("Other languages and full text"), g); g.gridy++;
        form.add(translate, g); g.gridy++;
        JPanel tpd = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tpd.add(new JLabel("At most ")); tpd.add(translatePerDay); tpd.add(new JLabel(" articles a day"));
        form.add(tpd, g); g.gridy++;
        form.add(note("Articles in Spanish, French, Russian, Arabic and other languages are translated into English so they group and link "
                + "with English coverage (the story page shows the original too). About 1-2 cents per 25 headlines with Claude Sonnet 5.5, within your "
                + "monthly budget. Without a key, names like Ucrania or Украина still match Ukraine."), g); g.gridy++;
        form.add(keepFull, g); g.gridy++;
        form.add(searchFull, g); g.gridy++;
        form.add(note("Some feeds (many blogs, some news sites) include the whole article. The app keeps that text and shows it under \"Full text\" "
                + "on the story page. Nothing is downloaded from the websites themselves, and nothing behind a paywall."), g); g.gridy++;

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
        if (!applyAlerts()) return;
        settings.setTranslate(translate.isSelected());
        settings.setTranslatePerDay((Integer) translatePerDay.getValue());
        settings.setKeepFullText(keepFull.isSelected());
        settings.setSearchFullText(searchFull.isSelected());
        settings.setPhoneFolder(phoneFolder.getText());
        settings.setClaudeDigest(digest.isSelected());
        settings.setClaudeCheckLinks((Integer) checkLinks.getValue());
        settings.save();
        saved = true;
        dispose();
    }

    /** Copies the notification and email fields into the settings; false (with a message) if one is invalid. */
    private boolean applyAlerts() {
        settings.setNotifications(notifications.isSelected());
        settings.setNotifyLinks(notifyLinks.isSelected());
        if (quiet.isSelected()) {
            LocalTime f = parse(quietFrom.getText()), t = parse(quietTo.getText());
            if (f == null || t == null) {
                JOptionPane.showMessageDialog(this, "Enter quiet hours as HH:MM, for example 22:00 to 07:00.", "Settings", JOptionPane.WARNING_MESSAGE);
                return false;
            }
            settings.setQuietHours(f, t);
        } else settings.setQuietHours(null, null);
        int port;
        try { port = Integer.parseInt(smtpPort.getText().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Enter the mail server port as a number, usually 587.", "Settings", JOptionPane.WARNING_MESSAGE); return false; }
        if (email.isSelected() && (smtpHost.getText().isBlank() || emailTo.getText().isBlank())) {
            JOptionPane.showMessageDialog(this, "To email the briefing, enter the mail server and who to send it to.", "Settings", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        settings.setEmailEnabled(email.isSelected());
        settings.setSmtp(smtpHost.getText(), port, (String) smtpSecurity.getSelectedItem(), smtpUser.getText(),
                emailFrom.getText().isBlank() ? smtpUser.getText() : emailFrom.getText(), emailTo.getText());
        try {
            String pw = new String(smtpPassword.getPassword());
            if (!pw.equals(settings.smtpPassword())) settings.setSmtpPassword(pw);
        } catch (IllegalStateException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private static LocalTime parse(String s) {
        try { s = s.trim(); return LocalTime.parse(s.length() == 4 ? "0" + s : s); } catch (DateTimeParseException e) { return null; }
    }

    private void sendTest(JButton button) {
        if (testEmail == null) return;
        if (smtpHost.getText().isBlank() || emailTo.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "Enter the mail server and who to send it to first.");
            return;
        }
        settings.reload();
        if (!applyAlerts()) return;
        settings.save();
        button.setEnabled(false);
        button.setText("Sending…");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return testEmail.call(); }
            @Override protected void done() {
                button.setEnabled(true);
                button.setText("Send test email now");
                try { JOptionPane.showMessageDialog(SettingsDialog.this, get() + "\nCheck the inbox (and the spam folder).", "Test email", JOptionPane.INFORMATION_MESSAGE); }
                catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(SettingsDialog.this, "The email wasn't sent:\n\n" + c.getMessage()
                            + "\n\nView > Logs has the conversation with the mail server.", "Test email", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
