package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search words in news-search style, used for archive search:
 *   iran oil              both words
 *   "strait of hormuz"    the exact phrase
 *   nvidia OR anthropic   either side
 *   hegseth -caracas      leave out articles with "caracas"
 * and field filters (any number, combined with the words; "-" in front leaves them out):
 *   source:bbc  source:"al jazeera"   outlet name contains
 *   desk:finance                      desk (world, finance, politics, society, tech, defense)
 *   after:2026-11-01  before:2026-11-20  after:3d   published after/before a day, or within the last N days
 *   region:europe                     where the outlet is based (regions.txt)
 *   is:unread  is:starred             what you haven't read / what you starred
 * Matching ignores case and accents and looks at the headline, summary and outlet name.
 * Words match at word starts, so "iran" matches "Iran" and "Iranian" but not "Tirana".
 */
public final class SearchQuery {
    private final List<List<Pattern>> anyOf = new ArrayList<>();   // OR of AND-groups
    private final List<List<String>> plain = new ArrayList<>();      // same groups as plain folded text, for the quick check
    private final List<Pattern> exclude = new ArrayList<>();
    private final List<String> highlightTerms = new ArrayList<>();
    private final String text;
    private final List<Field> fields = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final StringBuilder wordText = new StringBuilder();

    /** A field filter such as source:bbc or after:2026-11-01. */
    public record Field(String name, String value, boolean neg, long time) {}

    private static final java.util.Set<String> FIELD_NAMES = java.util.Set.of("source", "desk", "after", "before", "region", "is");
    private static final Pattern TOKEN = Pattern.compile("-?[A-Za-z]+:\"[^\"]*\"?|-?\"[^\"]*\"?|\\S+");
    private static final Pattern FIELD = Pattern.compile("(-?)([A-Za-z]+):(.+)");

    public SearchQuery(String text) {
        this.text = text == null ? "" : text.trim();
        List<Pattern> group = new ArrayList<>();
        List<String> words = new ArrayList<>();
        Matcher m = TOKEN.matcher(this.text);
        while (m.find()) {
            String tok = m.group();
            Matcher fm = FIELD.matcher(tok);
            if (fm.matches() && FIELD_NAMES.contains(fm.group(2).toLowerCase(Locale.ROOT))) {
                addField(fm.group(2).toLowerCase(Locale.ROOT), fm.group(3).replace("\"", "").trim(), !fm.group(1).isEmpty());
                continue;
            }
            if (wordText.length() > 0) wordText.append(' ');
            wordText.append(tok);
            if (tok.equals("OR") || tok.equals("|")) {
                if (!group.isEmpty()) { anyOf.add(group); plain.add(words); group = new ArrayList<>(); words = new ArrayList<>(); }
                continue;
            }
            boolean neg = tok.startsWith("-") && tok.length() > 1;
            if (neg) tok = tok.substring(1);
            String term = tok.replace("\"", "").trim();
            if (term.isEmpty()) continue;
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(fold(term)));
            if (neg) exclude.add(p);
            else { group.add(p); words.add(fold(term)); highlightTerms.add(term); }
        }
        if (!group.isEmpty()) { anyOf.add(group); plain.add(words); }
    }

    private void addField(String name, String value, boolean neg) {
        if (value.isEmpty()) { problems.add(name + ": needs a value"); return; }
        long time = 0;
        if (name.equals("after") || name.equals("before")) {
            try {
                if (value.matches("\\d+d")) {
                    time = System.currentTimeMillis() - Long.parseLong(value.substring(0, value.length() - 1)) * 86_400_000L;
                } else {
                    java.time.LocalDate d = java.time.LocalDate.parse(value);
                    // after: includes that day; before: excludes it.
                    time = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
            } catch (RuntimeException e) {
                problems.add(name + ": needs a date like 2026-11-01 or a number of days like 7d");
                return;
            }
        } else if (name.equals("desk")) {
            String v = value.toLowerCase(Locale.ROOT);
            if (!(v.startsWith("wor") || v.startsWith("fin") || v.startsWith("bus") || v.startsWith("eco") || v.startsWith("mark") || v.startsWith("pol")
                    || v.startsWith("soc") || v.startsWith("cli") || v.startsWith("hea") || v.startsWith("tech") || v.startsWith("sci")
                    || v.startsWith("def") || v.startsWith("spa") || v.startsWith("mil") || v.startsWith("aero"))) {
                problems.add("desk: use world, finance, politics, society, tech or defense");
                return;
            }
        } else if (name.equals("is")) {
            String v = value.toLowerCase(Locale.ROOT);
            if (!v.equals("unread") && !v.equals("read") && !v.equals("starred") && !v.equals("opinion")) { problems.add("is: use unread, read, starred or opinion"); return; }
        }
        fields.add(new Field(name, value, neg, time));
    }

    /** No words and no field filters. */
    public boolean isEmpty() { return anyOf.isEmpty() && fields.isEmpty(); }
    public boolean hasWords() { return !anyOf.isEmpty(); }
    /** The search words without the field filters (what is sent to live news search). */
    public String wordsText() { return wordText.toString().trim(); }
    public List<Field> fields() { return List.copyOf(fields); }
    /** Field filters that couldn't be understood (shown to you; they are ignored). */
    public List<String> problems() { return List.copyOf(problems); }

    /**
     * True if the article passes every field filter. {@code regionOf} maps an outlet to its region;
     * {@code read} and {@code starred} tell whether you've read or starred an article (may be null when unknown).
     */
    public boolean fieldsMatch(Article a, java.util.function.Function<String, String> regionOf,
                               java.util.function.Predicate<Article> read, java.util.function.Predicate<Article> starred) {
        for (Field f : fields) {
            boolean ok = switch (f.name()) {
                case "source" -> fold(a.source).contains(fold(f.value()));
                case "desk" -> {
                    com.linkeddesk.model.Category c = com.linkeddesk.model.Category.parse(f.value());
                    yield a.category() == c || a.feedCategory == c;
                }
                case "after" -> a.published >= f.time();
                case "before" -> a.published < f.time();
                case "region" -> regionOf != null && fold(regionOf.apply(a.source)).contains(fold(f.value()));
                case "is" -> switch (f.value().toLowerCase(Locale.ROOT)) {
                    case "unread" -> read == null || !read.test(a);
                    case "read" -> read != null && read.test(a);
                    case "opinion" -> Coverage.opinion(a);
                    default -> starred != null && starred.test(a);
                };
                default -> true;
            };
            if (ok == f.neg()) return false;
        }
        return true;
    }

    /** Words and fields (fields that need regions or your read state are skipped). */
    public boolean matchesAll(Article a) { return (anyOf.isEmpty() || matches(a)) && fieldsMatch(a, null, null, null); }

    /** True if the article contains one of the -excluded words (used to filter search-feed results). */
    public boolean excluded(Article a) {
        if (exclude.isEmpty()) return false;
        String hay = fold(a.title + " \n " + a.summary + " \n " + a.source);
        for (Pattern p : exclude) if (p.matcher(hay).find()) return true;
        return false;
    }
    public String text() { return text; }
    public List<String> highlightTerms() { return List.copyOf(highlightTerms); }

    public boolean matches(Article a) {
        if (anyOf.isEmpty()) return false;
        String raw = a.title + " \n " + a.summary + " \n " + a.source;
        if (cannotMatch(raw)) return false;
        String hay = fold(raw);
        for (Pattern p : exclude) if (p.matcher(hay).find()) return false;
        for (List<Pattern> g : anyOf) {
            boolean all = true;
            for (Pattern p : g) if (!p.matcher(hay).find()) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    /**
     * Fast pre-check on raw text (an archive line or headline+summary): true when no group's words can
     * all be present, so the slower accent-insensitive check can be skipped. Text with accented or other
     * non-ASCII letters is never rejected here, so accent folding still applies to it.
     */
    /** Text folded as matching sees it: lower case, accents removed. */
    public static String foldText(String s) { return fold(s); }

    /** True if the query leaves words out (-word). */
    public boolean hasExcludes() { return !exclude.isEmpty(); }

    /** The words each OR-group needs (folded), for an index to narrow the search; excluded words aren't listed. */
    public List<List<String>> wordGroups() {
        List<List<String>> out = new ArrayList<>();
        for (List<String> g : plain) out.add(List.copyOf(g));
        return out;
    }

    /** An article's text as matching sees it (folded once, for checking many queries against one article). */
    public static String folded(Article a) { return fold(a.title + " \n " + a.summary + " \n " + a.source); }

    /** Like {@link #matches(Article)}, for text from {@link #folded(Article)}. */
    public boolean matchesFolded(String hay) {
        if (anyOf.isEmpty()) return false;
        if (!plain.isEmpty()) {
            boolean any = false;
            for (List<String> g : plain) {
                boolean all = true;
                for (String w : g) if (!hay.contains(w)) { all = false; break; }
                if (all) { any = true; break; }
            }
            if (!any) return false;
        }
        for (Pattern p : exclude) if (p.matcher(hay).find()) return false;
        for (List<Pattern> g : anyOf) {
            boolean all = true;
            for (Pattern p : g) if (!p.matcher(hay).find()) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    public boolean cannotMatch(String raw) {
        if (plain.isEmpty()) return false;
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) > 127) return false;
        String low = raw.toLowerCase(Locale.ROOT);
        for (List<String> g : plain) {
            boolean all = true;
            for (String w : g) if (!low.contains(w)) { all = false; break; }
            if (all) return false;
        }
        return true;
    }

    /** Lower case without accents, so "Rodríguez" matches "rodriguez". */
    static String fold(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) { ascii = false; break; }
        if (ascii) return s.toLowerCase(Locale.ROOT);
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    /** Wraps matched words in &lt;b&gt; for display; input must already be HTML-escaped. */
    public String highlight(String escapedHtml) {
        String out = escapedHtml;
        for (String t : highlightTerms) {
            String et = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            out = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(" + Pattern.quote(et) + ")").matcher(out).replaceAll("<b>$1</b>");
        }
        return out;
    }
}
