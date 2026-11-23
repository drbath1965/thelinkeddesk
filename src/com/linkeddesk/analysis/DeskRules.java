package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Your desk corrections, kept in desk-rules.tsv. A rule moves stories to a desk and can also show them in a
 * second desk:
 * <ul>
 *   <li>STORY: one story (remembered by its article ids, so it stays moved after new downloads)</li>
 *   <li>SOURCE: every article from an outlet (e.g. all of "Defense News" in World)</li>
 *   <li>KEYWORD: every article whose headline contains a word or phrase (e.g. "Bitcoin" in Finance)</li>
 * </ul>
 * A story rule beats a keyword rule, which beats a source rule.
 */
public final class DeskRules {
    public enum Kind { STORY, SOURCE, KEYWORD }

    public record Rule(Kind kind, String key, Category desk, Category also, String label) {
        Set<String> ids() {
            Set<String> s = new LinkedHashSet<>();
            for (String x : key.split(",")) if (!x.isBlank()) s.add(x.strip());
            return s;
        }
        Pattern pattern() {
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(key.toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}])");
        }
        public String describe() {
            String where = switch (kind) {
                case STORY -> "Story \"" + label + "\"";
                case SOURCE -> "Everything from " + key;
                case KEYWORD -> "Headlines with \"" + key + "\"";
            };
            return where + " → " + desk.label() + (also != null ? " (also " + also.label() + ")" : "");
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private List<Pattern> keywordPatterns = List.of();

    public static final DeskRules NONE = new DeskRules();

    public List<Rule> rules() { return List.copyOf(rules); }
    public boolean isEmpty() { return rules.isEmpty(); }

    public void add(Rule r) {
        rules.removeIf(x -> x.kind() == r.kind() && (r.kind() == Kind.STORY ? overlap(x.ids(), r.ids()) : x.key().equalsIgnoreCase(r.key())));
        rules.add(r);
        compile();
    }

    public void remove(Rule r) { rules.remove(r); compile(); }

    private static boolean overlap(Set<String> a, Set<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    private void compile() {
        List<Pattern> ps = new ArrayList<>();
        for (Rule r : rules) ps.add(r.kind() == Kind.KEYWORD ? r.pattern() : null);
        keywordPatterns = ps;
    }

    /** The rule for one article (keyword beats source), or null. */
    Rule forArticle(Article a) {
        Rule source = null;
        String title = null;
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (r.kind() == Kind.KEYWORD) {
                if (title == null) title = a.title.toLowerCase(Locale.ROOT);
                if (keywordPatterns.get(i).matcher(title).find()) return r;
            } else if (r.kind() == Kind.SOURCE && source == null && r.key().equalsIgnoreCase(a.source)) {
                source = r;
            }
        }
        return source;
    }

    /** The story rule for this story, or null. */
    Rule forStory(StoryCluster c) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (r.kind() != Kind.STORY) continue;
            Set<String> ids = r.ids();
            for (Article a : c.articles) if (ids.contains(a.id)) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ file

    public static DeskRules load(Path file) throws IOException {
        DeskRules d = new DeskRules();
        if (!Files.exists(file)) return d;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 3) continue;
            try {
                Kind k = Kind.valueOf(p[0].strip().toUpperCase(Locale.ROOT));
                Category also = p.length > 3 && !p[3].isBlank() ? Category.parse(p[3]) : null;
                d.rules.add(new Rule(k, p[1].strip(), Category.parse(p[2]), also, p.length > 4 ? p[4] : ""));
            } catch (RuntimeException ignored) {
                // skip a damaged line
            }
        }
        d.compile();
        return d;
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("# Desk corrections. kind (STORY/SOURCE/KEYWORD)\tkey\tdesk\talso show in (optional)\tlabel\n");
        for (Rule r : rules) {
            sb.append(r.kind()).append('\t').append(r.key().replace('\t', ' ')).append('\t').append(r.desk().name()).append('\t')
              .append(r.also() == null ? "" : r.also().name()).append('\t').append(r.label().replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
