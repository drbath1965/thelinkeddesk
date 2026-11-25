package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coming up (ANL-F16): upcoming events mentioned in the news (votes, launches, deadlines, meetings, trials), found
 * offline from dates in headlines and summaries. A date counts only when it falls after the article was published
 * and its sentence talks about the future ("will", "is set to", "scheduled", "deadline"…), so "on Tuesday the
 * minister said" isn't taken for an event. Dates are read relative to the article's own publication date.
 */
public final class ComingUp {
    private ComingUp() {}

    public enum Precision { DAY, MONTH }

    public record Event(LocalDate date, Precision precision, String text, Article article) {
        public String when() {
            return precision == Precision.MONTH ? date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getYear()
                    : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date.getDayOfMonth();
        }
    }

    private static final String MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?";
    private static final String DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
    private static final Pattern MONTH_DAY = Pattern.compile("(?i)\\b(" + MONTHS + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(20\\d\\d))?");
    private static final Pattern DAY_MONTH = Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + MONTHS + ")\\b(?:,?\\s+(20\\d\\d))?");
    private static final Pattern WEEKDAY = Pattern.compile("(?i)\\b(?:(next|this|on|by|from|until|before)\\s+)?(" + DAYS + ")\\b");
    private static final Pattern TOMORROW = Pattern.compile("(?i)\\btomorrow\\b");
    private static final Pattern IN_MONTH = Pattern.compile("(?i)\\b(?:in|by|from|until|early|mid|late|end of)\\s+(?:early\\s+|mid-?|late\\s+)?(" + MONTHS + ")\\b(?:\\s+(20\\d\\d))?");
    private static final Pattern FUTURE = Pattern.compile("(?i)\\b(will|won't|shall|is set to|are set to|set for|scheduled|is due|are due|due to|expected to|expects to|plans? to|planned|is to|are to|to be held|deadline|upcoming|ahead of|next|later this|slated|poised to|aims to|intends to|could|would|vote on|vote is|hearing|summit|launch(?:es)?\\b|takes? effect|begins?|starts?|opens?|closes?|expires?)\\b");
    private static final Pattern PAST = Pattern.compile("(?i)\\b(said|says|told|announced|was|were|had|did|happened|killed|died|ended|took place|last|set a|set out|agreed|approved|voted|met)\\b");
    private static final Pattern YEAR_END = Pattern.compile("(?i)\\b(year-end|end of (?:the|this) year|by the end of the year|before the new year)\\b");
    private static final Pattern SENT = Pattern.compile("[^.!?]+[.!?]*");

    /** Events in these articles dated from {@code today} up to {@code days} ahead, soonest first, one per date and subject. */
    public static List<Event> find(Collection<Article> articles, LocalDate today, int days, ZoneId zone) {
        List<Event> all = new ArrayList<>();
        for (Article a : articles) {
            LocalDate pub = Instant.ofEpochMilli(a.published).atZone(zone).toLocalDate();
            for (String part : List.of(a.title == null ? "" : a.title, a.summary == null ? "" : a.summary)) {
                Matcher sm = SENT.matcher(part.replaceAll("\\s+", " "));
                while (sm.find()) {
                    String s = sm.group().strip();
                    if (s.length() < 15) continue;
                    for (Event e : inSentence(s, pub, a)) if (!e.date().isBefore(today) && !e.date().isAfter(today.plusDays(days))) all.add(e);
                }
            }
        }
        all.sort(Comparator.comparing(Event::date).thenComparing(e -> e.precision().ordinal()));
        List<Event> out = new ArrayList<>();
        for (Event e : all) {
            boolean dup = false;
            for (Event o : out) if (o.date().equals(e.date()) && similar(o.text(), e.text())) { dup = true; break; }
            if (!dup) out.add(e);
        }
        return out;
    }

    /** The future events one sentence mentions, read relative to the publication day. */
    public static List<Event> inSentence(String s, LocalDate pub, Article a) {
        List<Event> out = new ArrayList<>();
        boolean future = FUTURE.matcher(s).find();
        Set<LocalDate> seen = new HashSet<>();
        Matcher m = MONTH_DAY.matcher(s);
        while (m.find()) add(out, seen, date(month(m.group(1)), Integer.parseInt(m.group(2)), m.group(3), pub), Precision.DAY, s, a, pub, future);
        m = DAY_MONTH.matcher(s);
        while (m.find()) add(out, seen, date(month(m.group(2)), Integer.parseInt(m.group(1)), m.group(3), pub), Precision.DAY, s, a, pub, future);
        m = WEEKDAY.matcher(s);
        while (m.find()) {
            DayOfWeek d = DayOfWeek.valueOf(m.group(2).toUpperCase(Locale.ROOT));
            String q = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            LocalDate when = pub.with(TemporalAdjusters.next(d));
            if (q.equals("next") && when.isBefore(pub.plusDays(4))) when = when.plusWeeks(1);
            // "on Tuesday" alone is often the past ("said on Tuesday"): only within 3 days ahead, with a future verb and no past one
            boolean ok = q.equals("next") || (future && !PAST.matcher(s).find() && !when.isAfter(pub.plusDays(3)));
            if (ok) add(out, seen, when, Precision.DAY, s, a, pub, true);
        }
        if (TOMORROW.matcher(s).find()) add(out, seen, pub.plusDays(1), Precision.DAY, s, a, pub, future || !PAST.matcher(s).find());
        if (out.isEmpty() && future && YEAR_END.matcher(s).find() && pub.getMonth() != Month.DECEMBER)
            add(out, seen, LocalDate.of(pub.getYear(), 12, 1), Precision.MONTH, s, a, pub, true);
        if (out.isEmpty()) {
            m = IN_MONTH.matcher(s);
            while (m.find()) {
                Month mo = month(m.group(1));
                if (mo == null) continue;
                int y = m.group(2) != null ? Integer.parseInt(m.group(2)) : pub.getYear();
                LocalDate d = LocalDate.of(y, mo, 1);
                if (m.group(2) == null && d.plusMonths(1).isBefore(pub.plusDays(1))) d = d.plusYears(1);
                if (d.plusMonths(1).isAfter(pub.plusDays(1)) && future && !(d.getMonth() == pub.getMonth() && d.getYear() == pub.getYear()))
                    add(out, seen, d, Precision.MONTH, s, a, pub, true);
            }
        }
        return out;
    }

    private static void add(List<Event> out, Set<LocalDate> seen, LocalDate d, Precision p, String s, Article a, LocalDate pub, boolean future) {
        if (d == null || !future || !d.isAfter(pub) || d.isAfter(pub.plusDays(400)) || !seen.add(d)) return;
        String text = s.length() > 220 ? s.substring(0, 217) + "…" : s;
        out.add(new Event(d, p, text, a));
    }

    private static LocalDate date(Month mo, int day, String year, LocalDate pub) {
        if (mo == null || day < 1 || day > 31) return null;
        try {
            int y = year != null ? Integer.parseInt(year) : pub.getYear();
            LocalDate d = LocalDate.of(y, mo, Math.min(day, mo.length(Year.isLeap(y))));
            if (year == null && d.isBefore(pub.minusDays(60))) d = d.plusYears(1);   // "Jan 5" in a December article is next year
            return d;
        } catch (DateTimeException e) { return null; }
    }

    private static Month month(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        for (Month m : Month.values()) if (m.name().toLowerCase(Locale.ROOT).startsWith(k.length() >= 3 ? k.substring(0, 3) : k)) return m;
        return null;
    }

    private static boolean similar(String a, String b) {
        Set<String> x = QuickRead.words(a), y = QuickRead.words(b);
        int i = 0;
        for (String w : x) if (y.contains(w)) i++;
        int u = x.size() + y.size() - i;
        return u > 0 && (double) i / u >= 0.25;
    }
}
