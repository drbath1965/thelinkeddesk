package com.linkeddesk.feeds;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.model.Category;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes topics.txt. Each line: Desk | Topic name | Search words | Region (e.g. en-US) | on/paused.
 * Lines starting with # are comments. The file can be edited by hand or from the Topics screen.
 */
public final class TopicCatalog {
    private TopicCatalog() {}

    static final String HEADER = """
            # The Linked Desk custom topics
            # Format:  Desk | Topic name | Search words | Region | on or paused
            # Search words: plain words, "exact phrases", OR between alternatives, -word to exclude.
            # Region: language-country, e.g. en-US, en-GB, en-IN (default en-US).
            # Example:
            # World | Hypersonic weapons | hypersonic OR "glide vehicle" -game | en-US | on
            """;

    public static Path file() { return AppPaths.home().resolve("topics.txt"); }

    private static volatile boolean unreadable;

    public static List<Topic> load() {
        try {
            List<Topic> t = parse(com.linkeddesk.app.SafeFiles.readString(file()));
            unreadable = false;
            return t;
        } catch (IOException e) {
            unreadable = true;
            return new ArrayList<>();
        }
    }

    public static List<Topic> parse(String text) {
        List<Topic> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\|");
            if (p.length < 3 || p[1].isBlank() || p[2].isBlank()) continue;
            String region = p.length > 3 ? p[3].strip() : "en-US";
            boolean paused = p.length > 4 && p[4].strip().toLowerCase(Locale.ROOT).startsWith("pause");
            out.add(new Topic(Category.parse(p[0]), p[1].strip(), p[2].strip(), region, paused));
        }
        return out;
    }

    public static void save(List<Topic> topics) throws IOException {
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, file());
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Topic t : topics) sb.append(t.toLine()).append('\n');
        com.linkeddesk.app.SafeFiles.write(file(), sb.toString());
    }

    /** Validation used by the Topics screen and Save as topic. Returns an error message or null. */
    public static String validate(String name, String query, List<Topic> others) {
        if (name == null || name.isBlank()) return "Give the topic a name.";
        if (query == null || query.isBlank()) return "Enter the words to search for.";
        if (name.contains("|") || query.contains("|")) return "The | character can't be used in a topic.";
        for (Topic t : others) if (t.name().equalsIgnoreCase(name.trim())) return "There is already a topic called \"" + t.name() + "\".";
        return null;
    }
}
