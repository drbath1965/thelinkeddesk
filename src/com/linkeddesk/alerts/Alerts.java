package com.linkeddesk.alerts;

import com.linkeddesk.model.Article;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Notifications after a download (ALR-F02): one grouped message for all new watchlist matches (and, if you like,
 * new links between desks). During your quiet hours nothing pops up: the message is kept in pending-alerts.txt and
 * shown, combined with anything newer, after the quiet hours end.
 */
public final class Alerts {
    /** Shows a message (the Windows notification area in the app; a stand-in in tests). True if shown. */
    public interface Display { boolean show(String title, String text); }

    public record Alert(String title, String text, int stories) {
        public boolean isEmpty() { return stories == 0; }
    }

    private final Path pending;
    private final Display display;

    public Alerts(Path home, Display display) {
        this.pending = home.resolve("pending-alerts.txt");
        this.display = display;
    }

    /** One message for everything new: "3 new on your watchlist" / "Hormuz 2 · Fed 1 — first headline". */
    public static Alert compose(Map<Watches.Watch, List<Article>> hits, int newCrossDeskLinks) {
        int n = 0;
        StringBuilder parts = new StringBuilder();
        String first = null;
        for (var e : hits.entrySet()) {
            n += e.getValue().size();
            if (parts.length() > 0) parts.append(" · ");
            parts.append(e.getKey().name()).append(' ').append(e.getValue().size());
            if (first == null && !e.getValue().isEmpty()) first = e.getValue().get(0).title;
        }
        if (n == 0 && newCrossDeskLinks <= 0) return new Alert("", "", 0);
        String title = n == 0 ? "New links across desks"
                : n + (n == 1 ? " new article" : " new articles") + " on your watchlist";
        StringBuilder text = new StringBuilder(parts);
        if (first != null) text.append("\n").append(first.length() > 110 ? first.substring(0, 108) + "…" : first);
        if (newCrossDeskLinks > 0) text.append(text.length() > 0 ? "\n" : "").append(newCrossDeskLinks)
                .append(newCrossDeskLinks == 1 ? " new link between desks" : " new links between desks");
        return new Alert(title, text.toString(), Math.max(1, n));
    }

    /** True if {@code now} is within quiet hours from-to (which may run past midnight); null = no quiet hours. */
    public static boolean quiet(LocalTime now, LocalTime from, LocalTime to) {
        if (from == null || to == null || from.equals(to)) return false;
        return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
    }

    /**
     * Shows the alert now, or keeps it for later during quiet hours. Anything kept from before is shown with it.
     * Returns what was shown (null if nothing).
     */
    public synchronized Alert deliver(Alert a, LocalTime now, LocalTime quietFrom, LocalTime quietTo) throws IOException {
        if (quiet(now, quietFrom, quietTo)) {
            if (!a.isEmpty()) Files.writeString(pending, a.title() + "\t" + a.text().replace("\n", "\\n") + "\t" + a.stories() + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return null;
        }
        List<Alert> all = new ArrayList<>(held());
        if (!a.isEmpty()) all.add(a);
        if (all.isEmpty()) return null;
        Alert show = all.size() == 1 ? all.get(0) : combine(all);
        if (display.show(show.title(), show.text())) Files.deleteIfExists(pending);
        return show;
    }

    /** Messages kept during quiet hours. */
    public synchronized List<Alert> held() throws IOException {
        List<Alert> out = new ArrayList<>();
        if (!Files.exists(pending)) return out;
        for (String l : Files.readAllLines(pending, StandardCharsets.UTF_8)) {
            String[] p = l.split("\t");
            if (p.length == 3) try { out.add(new Alert(p[0], p[1].replace("\\n", "\n"), Integer.parseInt(p[2]))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static Alert combine(List<Alert> all) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Alert a : all) {
            n += a.stories();
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.text().split("\n")[0]);
        }
        return new Alert("While you were away: " + n + (n == 1 ? " new article" : " new articles") + " on your watchlist", sb.toString(), n);
    }
}
