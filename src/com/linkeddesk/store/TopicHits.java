package com.linkeddesk.store;

import com.linkeddesk.app.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which custom topic found which article (data/topic-hits.tsv: topic, article id, time found).
 * An article can be found by several topics and also by a regular feed; the article itself is stored once.
 * Hits are kept when a topic is deleted, so its old articles stay findable.
 */
public final class TopicHits {
    private final Path file;
    private final Map<String, Set<String>> byTopic = new HashMap<>();
    private final Map<String, Long> firstSeen = new HashMap<>();   // topic|id -> time found

    public TopicHits() { this(AppPaths.dataDir().resolve("topic-hits.tsv")); }
    public TopicHits(Path file) { this.file = file; }

    public synchronized void load() throws IOException {
        byTopic.clear();
        firstSeen.clear();
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            try { remember(p[0], p[1], Long.parseLong(p[2])); } catch (NumberFormatException ignored) { }
        }
    }

    /** Records that {@code topic} found these articles; returns how many were new for this topic. */
    public synchronized int add(String topic, Collection<String> articleIds, long when) throws IOException {
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (String id : articleIds) {
            if (remember(topic, id, when)) {
                sb.append(topic.replace('\t', ' ')).append('\t').append(id).append('\t').append(when).append('\n');
                added++;
            }
        }
        if (added > 0) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return added;
    }

    private boolean remember(String topic, String id, long when) {
        if (!byTopic.computeIfAbsent(topic, k -> new LinkedHashSet<>()).add(id)) return false;
        firstSeen.put(topic + "|" + id, when);
        return true;
    }

    public synchronized Set<String> articles(String topic) {
        return Set.copyOf(byTopic.getOrDefault(topic, Set.of()));
    }

    /** Number of articles a topic found since {@code t}. */
    public synchronized int countSince(String topic, long t) {
        int n = 0;
        for (String id : byTopic.getOrDefault(topic, Set.of())) {
            Long w = firstSeen.get(topic + "|" + id);
            if (w != null && w >= t) n++;
        }
        return n;
    }
}
