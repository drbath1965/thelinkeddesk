package com.linkeddesk.ai;

import com.linkeddesk.model.StoryCluster;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Questions across the archive (AI-F04): the app finds the saved stories that match the question, sends them as
 * [S1], [S2]…, and Claude answers from them only, citing each claim. Citations to stories that weren't sent are
 * removed, so every citation opens a story that exists in your archive.
 */
public final class ArchiveQA {
    private ArchiveQA() {}

    public static final int MAX_TOKENS = 700;
    public static final int MAX_STORIES = 14;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());

    public record Answer(String question, String text, Map<String, StoryCluster> refs, List<String> cited, List<String> problems,
                         int articlesSearched) {
        /** True when the archive had nothing relevant and Claude wasn't asked. */
        public boolean nothingFound() { return refs.isEmpty(); }
    }

    public static String prompt(String question, Map<String, StoryCluster> refs) {
        StringBuilder sb = new StringBuilder("""
                Answer the reader's question using only the news stories below, which come from the reader's own news \
                archive. After each claim, cite the stories it rests on, like [S4]. If the stories don't answer the \
                question, say so plainly and say what they do cover. Mark your own reading or inference with \
                "Inference:". Under 200 words, plain text.

                QUESTION: """).append(question.strip()).append("\n\nSTORIES\n");
        for (var e : refs.entrySet()) {
            StoryCluster c = e.getValue();
            sb.append('[').append(e.getKey()).append("] ").append(DAY.format(Instant.ofEpochMilli(c.firstSeen)));
            if (c.lastSeen - c.firstSeen > 86_400_000L) sb.append(" to ").append(DAY.format(Instant.ofEpochMilli(c.lastSeen)));
            sb.append(", ").append(c.category.label()).append(" desk, ").append(c.sourceCount())
              .append(c.sourceCount() == 1 ? " outlet: " : " outlets: ").append(c.headline).append('\n')
              .append(ClaudeClient.describe(c, 3, 220));
        }
        return sb.toString();
    }

    public static Answer parse(String question, String text, Map<String, StoryCluster> refs, int searched) {
        List<String> problems = new ArrayList<>();
        String clean = Cite.clean(text, refs.keySet(), problems);
        return new Answer(question, clean, refs, Cite.cited(clean), problems, searched);
    }
}
