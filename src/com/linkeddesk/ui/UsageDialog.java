package com.linkeddesk.ui;

import com.linkeddesk.ai.ClaudeService;
import com.linkeddesk.ai.Pricing;
import com.linkeddesk.service.NewsService;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Tools > Claude usage: this month's spend against the budget, per feature, and the latest requests. */
final class UsageDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());

    UsageDialog(Window owner, NewsService service) {
        super(owner, "Claude usage", ModalityType.APPLICATION_MODAL);
        ClaudeService c = service.claude();
        double[] price = c.prices();
        StringBuilder sb = new StringBuilder("<html><head><style>").append(Html.css()).append("</style></head><body>");
        sb.append("<h1>").append(Html.esc(c.summary())).append("</h1>");
        sb.append("<p class='meta'>Model ").append(Html.esc(service.settings().model())).append(String.format(" at $%.2f per million input tokens and $%.2f per million output tokens", price[0], price[1]))
          .append(Pricing.known(service.settings().model()) && service.settings().priceIn() == 0 ? " (Anthropic list price)." : " (your prices, set in Settings).")
          .append(" Before each request the app estimates its largest possible cost and doesn't send it if that would pass the budget. ")
          .append("Answers are cached: ").append(c.cachedAnswers()).append(" saved, so the same question is never paid for twice.</p>");
        sb.append("<h2>By feature this month</h2><table>");
        var by = c.byFeature();
        if (by.isEmpty()) sb.append("<tr><td class='meta'>No requests yet this month.</td></tr>");
        for (var e : by.entrySet()) {
            String name = switch (e.getKey()) {
                case "digest" -> "Daily digest"; case "check" -> "Link checks"; case "ask" -> "Questions (Ask tab)";
                case "explain" -> "Ask Claude why"; default -> e.getKey();
            };
            sb.append("<tr><td width='200'>").append(name).append("</td><td width='110'>").append((int) e.getValue()[0]).append(e.getValue()[0] == 1 ? " request" : " requests")
              .append("</td><td>").append(String.format("$%.3f", e.getValue()[1])).append("</td></tr>");
        }
        sb.append("</table><h2>Latest requests</h2><table>");
        List<ClaudeService.Call> calls = c.calls(YearMonth.now());
        for (int i = calls.size() - 1; i >= Math.max(0, calls.size() - 25); i--) {
            ClaudeService.Call k = calls.get(i);
            sb.append("<tr><td width='140' class='meta'>").append(WHEN.format(Instant.ofEpochMilli(k.time()))).append("</td><td width='90'>").append(Html.esc(k.feature()))
              .append("</td><td width='220' class='meta'>").append(String.format("%,d in / %,d out tokens", k.in(), k.out())).append("</td><td>")
              .append(String.format("$%.4f", k.cost())).append("</td></tr>");
        }
        sb.append("</table><p class='act'>Kept in claude-usage.tsv next to feeds.txt. Change the budget in Tools &gt; Settings.</p></body></html>");
        JEditorPane p = StoryPane.htmlPane();
        p.setText(sb.toString());
        p.setCaretPosition(0);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);
        getContentPane().add(new JScrollPane(p), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(760, 560);
        setLocationRelativeTo(owner);
    }
}
