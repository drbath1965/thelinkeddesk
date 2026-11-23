package com.linkeddesk.ui;

import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.Thresholds;
import com.linkeddesk.model.Category;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tools > Calibrate linking: how well grouping and linking score on a labelled sample of real news and on
 * your own verdicts, and a search for better thresholds per desk.
 */
final class CalibrationDialog extends JDialog {
    private final Host host;
    private final JEditorPane pane = StoryBrowser.htmlPane();
    private final JButton find = new JButton("Find best settings");
    private final JButton use = new JButton("Use suggested settings");
    private final JButton reset = new JButton("Restore defaults");
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Calibrator.Suggestion suggestion;
    private Calibrator.Metrics current;
    private Calibrator.VerdictScore currentYours;
    private boolean running;
    private String sampleSize = "real news";

    CalibrationDialog(Window owner, Host host) {
        super(owner, "Calibrate linking", ModalityType.MODELESS);
        this.host = host;
        use.setEnabled(false);
        find.addActionListener(e -> { if (running) cancel.set(true); else runSearch(); });
        use.addActionListener(e -> {
            if (suggestion == null) return;
            save(suggestion.thresholds(), "Calibrated settings saved. Regrouping…");
        });
        reset.addActionListener(e -> save(Thresholds.defaults(), "Default settings restored. Regrouping…"));
        JButton close = new JButton("Close");
        close.addActionListener(e -> { cancel.set(true); dispose(); });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(reset); south.add(find); south.add(use); south.add(close);
        getContentPane().add(new JScrollPane(pane), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(900, 720);
        setLocationRelativeTo(owner);
        refresh();
    }

    private void save(Thresholds t, String msg) {
        try {
            host.service().saveThresholds(t);
            suggestion = null;
            use.setEnabled(false);
            host.status(msg);
            host.correctionsChanged();
            refresh();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    /** Scores the current settings (in the background). */
    void refresh() {
        pane.setText(Html.empty("Scoring the current settings…"));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Thresholds t = host.service().options().thresholds();
                current = host.service().sampleMetrics(t);
                Calibrator.Sample smp = Calibrator.bundled();
                sampleSize = smp.items().size() + " headlines from " + smp.articles().stream().map(x -> x.source).distinct().count()
                        + " outlets, Nov 2026, " + smp.items().stream().map(Calibrator.Labelled::label).distinct().count() + " events";
                currentYours = host.service().verdictScore(t);
                return null;
            }
            @Override protected void done() {
                try { get(); render(null); }
                catch (Exception ex) { pane.setText(Html.empty("Could not score the settings: " + ex.getMessage())); }
            }
        }.execute();
    }

    private void runSearch() {
        running = true;
        cancel.set(false);
        find.setText("Stop");
        use.setEnabled(false);
        new SwingWorker<Calibrator.Suggestion, String>() {
            @Override protected Calibrator.Suggestion doInBackground() throws Exception {
                return host.service().calibrate(this::publish, cancel::get);
            }
            @Override protected void process(java.util.List<String> chunks) { render(chunks.get(chunks.size() - 1)); }
            @Override protected void done() {
                running = false;
                find.setText("Find best settings");
                try {
                    suggestion = get();
                    use.setEnabled(!suggestion.thresholds().equals(host.service().options().thresholds()));
                    render(cancel.get() ? "Stopped early: these are the best settings found so far." : null);
                } catch (Exception ex) {
                    render("The search failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void render(String progress) {
        Thresholds t = host.service().options().thresholds();
        StringBuilder sb = new StringBuilder("<html><head><style>").append(Html.css()).append("</style></head><body>");
        sb.append("<h1>How well stories are grouped and linked</h1>");
        sb.append("<p class='meta'>Scored on a labelled sample of real news (").append(Html.esc(sampleSize))
          .append("; each article marked with the event it reports) and on the verdicts you gave in the app. ")
          .append("Targets: grouping precision at least ").append(pct(Calibrator.TARGET_GROUP_PRECISION))
          .append(", link precision at least ").append(pct(Calibrator.TARGET_LINK_PRECISION)).append(".</p>");
        if (progress != null) sb.append("<p><b>").append(Html.esc(progress)).append("</b></p>");

        sb.append("<h2>Current settings</h2>");
        if (current != null) sb.append(metrics(current, currentYours));
        sb.append(table(t, null));

        if (suggestion != null) {
            sb.append("<h2>Suggested settings</h2>");
            if (suggestion.thresholds().equals(t)) sb.append("<p>The current settings are already the best found.</p>");
            else {
                sb.append(metrics(suggestion.sample(), suggestion.yours()));
                sb.append(table(suggestion.thresholds(), t));
                sb.append("<p class='meta'>Click <b>Use suggested settings</b> to apply them. They are saved in calibration.properties; ")
                  .append("<b>Restore defaults</b> undoes it.</p>");
            }
        }
        sb.append("<h2>What the numbers mean</h2><p class='meta'><b>Grouping precision</b>: of the pairs of articles put in the same story, ")
          .append("the share that really report the same event. <b>Recall</b>: of the articles that report the same event, the share grouped together. ")
          .append("<b>Link precision</b>: of the links made, the share between related stories. <b>Link recall</b>: of the stories that have a related ")
          .append("story nearby, the share that got at least one right link. Each verdict you give (✓ Right / ✗ Wrong on links, ")
          .append("Not this story on articles) is used the next time you click Find best settings.</p>");
        pane.setText(sb.append("</body></html>").toString());
        pane.setCaretPosition(0);
    }

    private static String metrics(Calibrator.Metrics m, Calibrator.VerdictScore v) {
        StringBuilder sb = new StringBuilder("<table>");
        row(sb, "Grouping precision", pct(m.groupPrecision()), m.groupPrecision() >= Calibrator.TARGET_GROUP_PRECISION,
                m.groupCorrect() + " of " + m.groupPairs() + " article pairs");
        row(sb, "Grouping recall", pct(m.groupRecall()), null, m.groupCorrect() + " of " + m.goldPairs() + " same-event pairs grouped");
        row(sb, "Link precision", pct(m.linkPrecision()), m.linkPrecision() >= Calibrator.TARGET_LINK_PRECISION,
                m.linksCorrect() + " of " + m.links() + " links");
        row(sb, "Link recall", pct(m.linkRecall()), null, m.relatedLinked() + " of " + m.relatedStories() + " stories with a related story got a right link");
        row(sb, "Storylines", m.storylineCorrect() + " of " + m.storylineSteps(), null, "day-to-day connections that are right");
        sb.append("<tr><td><b>Your verdicts</b></td><td colspan='2' class='meta'>").append(Html.esc(v == null ? "—" : v.summary())).append("</td></tr>");
        return sb.append("</table>").toString();
    }

    private static void row(StringBuilder sb, String what, String value, Boolean ok, String detail) {
        sb.append("<tr><td width='160'><b>").append(what).append("</b></td><td width='90'")
          .append(ok == null ? "" : ok ? " class='good'" : " class='bad'").append(">").append(value)
          .append(ok == null ? "" : ok ? " ✓" : " ✗").append("</td><td class='meta'>").append(Html.esc(detail)).append("</td></tr>");
    }

    private static String table(Thresholds t, Thresholds was) {
        StringBuilder sb = new StringBuilder("<table><tr><td><b>Desk</b></td><td><b>Same event when similarity ≥</b></td><td><b>Link when shared words ≥</b></td></tr>");
        for (Category c : Category.values()) {
            sb.append("<tr><td>").append(c.label()).append("</td><td>").append(num(t.sameEvent(c), was == null ? null : was.sameEvent(c)))
              .append("</td><td>").append(num(t.link(c), was == null ? null : was.link(c))).append("</td></tr>");
        }
        sb.append("<tr><td>Storylines</td><td colspan='2'>day-to-day connection when similarity ≥ ")
          .append(num(t.lineage(), was == null ? null : was.lineage())).append("</td></tr>");
        return sb.append("</table>").toString();
    }

    private static String num(double v, Double was) {
        String s = String.format("%.2f", v);
        if (was == null || Math.abs(was - v) < 1e-9) return s;
        return "<b>" + s + "</b> <span class='meta'>(was " + String.format("%.2f", was) + ")</span>";
    }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }
}
