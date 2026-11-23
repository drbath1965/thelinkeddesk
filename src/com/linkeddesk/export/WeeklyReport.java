package com.linkeddesk.export;

import com.linkeddesk.ai.Digest;
import com.linkeddesk.alerts.EmailDigest;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.model.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * The weekly report (EXP-F02): the week's top stories per desk, how stories developed, the links between desks,
 * the threads, your watchlist and tickers, and what Claude's daily digests flagged. Any week in the archive.
 */
public final class WeeklyReport {
    private WeeklyReport() {}

    public static final int PER_DESK = 5;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMMM d", Locale.US);
    private static final DateTimeFormatter DY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US).withZone(ZoneId.systemDefault());

    /** Monday of the week holding this day. */
    public static LocalDate weekStart(LocalDate any) { return any.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }

    public static long startMillis(LocalDate monday) { return monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    public static long endMillis(LocalDate monday) { return startMillis(monday.plusDays(7)); }

    /** "November 16–22, 2026" (or across months / years). */
    public static String label(LocalDate monday) {
        LocalDate sun = monday.plusDays(6);
        if (monday.getYear() != sun.getYear()) return DY.format(monday) + " – " + DY.format(sun);
        if (monday.getMonth() != sun.getMonth()) return D.format(monday) + " – " + DY.format(sun);
        return D.format(monday) + "–" + sun.getDayOfMonth() + ", " + sun.getYear();
    }

    /** What goes into the report besides the week's grouped stories. */
    public record Extras(Regions regions, ToDoubleFunction<StoryCluster> coverage, Map<String, List<StoryCluster>> watches,
                         Map<String, List<StoryCluster>> tickers, List<Digest> digests) {}

    public static Doc build(LocalDate monday, Analyzer.Result r, Extras x) {
        String title = "The Linked Desk · Week of " + label(monday);
        Doc d = new Doc(title);
        d.h1("Week of " + label(monday));
        List<StoryCluster> all = new ArrayList<>(r.stories());
        Set<String> outlets = new HashSet<>();
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        for (StoryCluster c : all) for (Article a : c.articles) {
            outlets.add(a.source);
            perDay.merge(Instant.ofEpochMilli(a.published).atZone(ZoneId.systemDefault()).toLocalDate(), 1, Integer::sum);
        }
        long wide = all.stream().filter(c -> c.sourceCount() >= 3).count();
        Set<String> crossPairs = new HashSet<>();
        List<StoryLink> cross = new ArrayList<>();
        for (StoryCluster c : all) for (StoryLink l : c.links) {
            if (!l.crossDesk()) continue;
            String k = c.id.compareTo(l.to().id) < 0 ? c.id + "|" + l.to().id : l.to().id + "|" + c.id;
            if (crossPairs.add(k)) cross.add(l);
        }
        if (all.isEmpty()) {
            d.p().text("Nothing was saved for this week. Pick a week inside your archive.");
            return d;
        }
        LocalDate busiest = perDay.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        d.p().text(String.format("%,d articles from %,d outlets, grouped into %,d stories (%,d covered by three or more outlets). %,d links between desks. "
                        + "Busiest day: %s (%,d articles).", r.articleCount(), outlets.size(), all.size(), wide, cross.size(),
                DateTimeFormatter.ofPattern("EEEE", Locale.US).format(busiest), perDay.get(busiest)));
        Comparator<StoryCluster> byCoverage = Comparator.comparingDouble((StoryCluster c) -> x.coverage().applyAsDouble(c)).reversed()
                .thenComparing(Comparator.comparingInt((StoryCluster c) -> c.articles.size()).reversed());

        // Top stories per desk
        d.h2("Top stories");
        for (Category desk : Category.values()) {
            List<StoryCluster> s = all.stream().filter(c -> c.inDesk(desk)).sorted(byCoverage).limit(PER_DESK).toList();
            if (s.isEmpty()) continue;
            d.h3(desk.label());
            for (StoryCluster c : s) storyLine(d, c, x.regions(), desk);
        }

        // Stories that developed over the week
        List<List<StoryCluster>> lines = new ArrayList<>();
        Set<StoryCluster> used = new HashSet<>();
        for (StoryCluster c : all) {
            if (used.contains(c)) continue;
            List<StoryCluster> line = c.storyline();
            used.addAll(line);
            if (line.size() >= 2) lines.add(line);
        }
        lines.sort(Comparator.comparingInt((List<StoryCluster> l) -> l.stream().mapToInt(c -> c.articles.size()).sum()).reversed());
        if (!lines.isEmpty()) {
            d.h2("How stories developed");
            for (List<StoryCluster> line : lines.subList(0, Math.min(5, lines.size()))) {
                Doc.P p = d.bullet();
                for (int i = 0; i < line.size(); i++) {
                    StoryCluster c = line.get(i);
                    if (i > 0) p.br();
                    p.muted(DAY.format(Instant.ofEpochMilli(c.firstSeen)) + ": ").link(c.headline, EmailDigest.lead(c).url);
                }
            }
        }

        // Links across desks
        if (!cross.isEmpty()) {
            cross.sort(Comparator.comparingDouble(StoryLink::score).reversed());
            d.h2("Connections across desks");
            for (StoryLink l : cross.subList(0, Math.min(10, cross.size()))) {
                d.bullet().link(l.from().headline, EmailDigest.lead(l.from()).url).muted(" (" + l.from().category.label() + ")").br()
                        .text("↔ ").link(l.to().headline, EmailDigest.lead(l.to()).url).muted(" (" + l.to().category.label() + ") · shared: " + String.join(", ", l.sharedTerms()));
            }
        }

        // Threads
        List<StoryThread> threads = new ArrayList<>(r.threads());
        threads.sort(Comparator.comparingInt((StoryThread t) -> t.stories().size()).reversed());
        if (!threads.isEmpty()) {
            d.h2("Threads");
            for (StoryThread t : threads.subList(0, Math.min(6, threads.size()))) {
                Set<String> desks = new TreeSet<>();
                for (Category c : t.desks()) desks.add(c.label());
                StoryCluster top = t.stories().stream().max(Comparator.comparingInt(StoryCluster::sourceCount)).get();
                d.bullet().bold(t.topic()).muted(" · " + t.stories().size() + " stories in " + String.join(", ", desks) + " · biggest: ").link(top.headline, EmailDigest.lead(top).url);
            }
        }

        // Watchlist and tickers
        listOf(d, "Your watchlist", x.watches(), byCoverage);
        listOf(d, "Markets", x.tickers(), byCoverage);

        // Claude's digests
        List<String> watch = new ArrayList<>();
        for (Digest dg : x.digests()) for (String w : dg.watch()) watch.add(DAY.format(dg.day().atStartOfDay(ZoneId.systemDefault()).toInstant()) + ": "
                + com.linkeddesk.ai.Cite.TAG.matcher(w).replaceAll("").replaceAll("\\s+", " ").strip());
        if (!watch.isEmpty()) {
            d.h2("What Claude's daily digests said to watch");
            for (String w : watch) d.bullet().text(w);
        }
        d.rule();
        d.small().muted("Made by The Linked Desk on " + Exporter.WHEN.format(Instant.now()) + " from the articles saved on your PC. Coverage counts each outlet once"
                + " (or by the weight you gave it in Tools > Sources).");
        return d;
    }

    private static void storyLine(Doc d, StoryCluster c, Regions regions, Category desk) {
        Regions.Spread sp = regions.spread(c);
        Set<LocalDate> days = new TreeSet<>();
        for (Article a : c.articles) days.add(Instant.ofEpochMilli(a.published).atZone(ZoneId.systemDefault()).toLocalDate());
        d.bullet().boldLink(c.headline, EmailDigest.lead(c).url).br()
                .muted(Exporter.coverage(c, sp) + " · reported on " + days.size() + (days.size() == 1 ? " day" : " days")
                        + (c.category != desk ? " · from " + c.category.label() : c.also != null ? " · also in " + c.also.label() : ""));
    }

    private static void listOf(Doc d, String title, Map<String, List<StoryCluster>> m, Comparator<StoryCluster> order) {
        if (m == null || m.isEmpty()) return;
        d.h2(title);
        for (var e : m.entrySet()) {
            List<StoryCluster> s = new ArrayList<>(e.getValue());
            s.sort(order);
            Doc.P p = d.bullet().bold(e.getKey()).muted(" · " + s.size() + (s.size() == 1 ? " story" : " stories"));
            for (StoryCluster c : s.subList(0, Math.min(3, s.size()))) p.br().link(c.headline, EmailDigest.lead(c).url);
        }
    }
}
