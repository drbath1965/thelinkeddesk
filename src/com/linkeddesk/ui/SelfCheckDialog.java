package com.linkeddesk.ui;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.service.SelfCheck;
import com.linkeddesk.service.UpdateCheck;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/** Help > Check this PC (OPS-F06): runs the checks in the background and shows each with how to fix it. */
final class SelfCheckDialog extends JDialog {
    private final NewsService service;
    private final JEditorPane pane = StoryBrowser.htmlPane();
    private final JLabel summary = new JLabel("Checking…");
    private List<SelfCheck.Check> checks;
    private SelfCheck.Facts facts;

    SelfCheckDialog(Window owner, NewsService service, Callable<String> testEmail, Runnable testNotification) {
        super(owner, "Check this PC", ModalityType.MODELESS);
        this.service = service;
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, summary.getFont().getSize2D() * 1.25f));
        summary.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JScrollPane sp = new JScrollPane(pane);
        sp.setPreferredSize(new Dimension(Math.round(760 * Theme.scale), Math.round(520 * Theme.scale)));
        JButton again = new JButton("Check again"), save = new JButton("Save report…"), mail = new JButton("Send a test email"),
                note = new JButton("Show a test notification"), close = new JButton("Close");
        again.addActionListener(e -> run());
        save.addActionListener(e -> save());
        mail.addActionListener(e -> {
            mail.setEnabled(false);
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return testEmail.call(); }
                @Override protected void done() {
                    mail.setEnabled(true);
                    try { JOptionPane.showMessageDialog(SelfCheckDialog.this, get() + "\nCheck the inbox (and the spam folder).", "Test email", JOptionPane.INFORMATION_MESSAGE); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() == null ? ex : ex.getCause();
                        JOptionPane.showMessageDialog(SelfCheckDialog.this, "The email wasn't sent:\n\n" + c.getMessage(), "Test email", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });
        note.addActionListener(e -> {
            testNotification.run();
            int ok = JOptionPane.showConfirmDialog(this, "A test notification was just sent. Did it appear near the clock?", "Test notification", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.NO_OPTION) JOptionPane.showMessageDialog(this, """
                    Windows may be holding notifications back. Check:
                    • Settings > System > Notifications: notifications on, and Java (or The Linked Desk) allowed.
                    • Focus assist / Do not disturb is off.
                    • The app's quiet hours (Tools > Settings > Alerts).""", "Test notification", JOptionPane.INFORMATION_MESSAGE);
        });
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        for (JButton b : new JButton[]{again, mail, note, save, close}) buttons.add(b);
        getContentPane().add(summary, BorderLayout.NORTH);
        getContentPane().add(sp, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
        run();
    }

    private void run() {
        summary.setText("Checking…");
        pane.setText("<html><body><p class='meta'>Checking Java, the daily task, the saved key, feeds, the data folder, backups and the screen…</p></body></html>");
        Window owner = getOwner();
        new SwingWorker<SelfCheck.Facts, Void>() {
            @Override protected SelfCheck.Facts doInBackground() {
                SelfCheck.Facts f = SelfCheck.gather(service, owner);
                try {
                    var r = new UpdateCheck().latest();
                    f.latestVersion = r == null ? AppPaths.VERSION : r.version();
                } catch (Exception e) { f.updateError = e.getMessage() == null ? e.toString() : e.getMessage(); }
                return f;
            }
            @Override protected void done() {
                try { facts = get(); } catch (Exception e) { summary.setText("The check failed: " + e.getMessage()); return; }
                checks = SelfCheck.evaluate(facts);
                show(checks);
            }
        }.execute();
    }

    private void show(List<SelfCheck.Check> checks) {
        long fails = checks.stream().filter(c -> c.status() == SelfCheck.Status.FAIL).count();
        long warns = checks.stream().filter(c -> c.status() == SelfCheck.Status.WARN).count();
        summary.setText(fails + warns == 0 ? "✔ Everything checked is working." : (fails > 0 ? fails + (fails == 1 ? " problem" : " problems") : "") + (fails > 0 && warns > 0 ? " and " : "") + (warns > 0 ? warns + (warns == 1 ? " thing to look at" : " things to look at") : "") + ".");
        summary.setForeground(Theme.c(fails > 0 ? Theme.bad() : warns > 0 ? Theme.warn() : Theme.ok()));
        StringBuilder sb = new StringBuilder("<html><head><style>").append(Html.css()).append("""
                td.s{width:26px;font-weight:bold;text-align:center} """ + ".OK{color:" + Theme.ok() + "} .WARN{color:" + Theme.warn() + "} .FAIL{color:" + Theme.bad() + "} .INFO{color:" + Theme.muted() + "}" + """

                </style></head><body><table cellspacing='0' cellpadding='5'>""");
        for (SelfCheck.Check c : checks) {
            sb.append("<tr><td class='s ").append(c.status()).append("' valign='top'>").append(c.icon()).append("</td><td valign='top'><b>")
              .append(Html.esc(c.name())).append("</b><br>").append(Html.esc(c.detail()));
            if (!c.fix().isBlank()) sb.append("<br><span class='act'>Fix: ").append(Html.esc(c.fix())).append("</span>");
            sb.append("</td></tr>");
        }
        pane.setText(sb.append("</table></body></html>").toString());
        pane.setCaretPosition(0);
    }

    private void save() {
        if (checks == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("linkeddesk-check-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path p = fc.getSelectedFile().toPath();
            Files.writeString(p, SelfCheck.report(checks, facts));
            JOptionPane.showMessageDialog(this, "Saved " + p + ".");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Couldn't save the report: " + e.getMessage(), "Save report", JOptionPane.ERROR_MESSAGE);
        }
    }
}
