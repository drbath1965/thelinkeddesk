package com.linkeddesk.store;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What you've read and what you've starred, kept in data/read.txt (article ids, one per line, only ever
 * appended) and data/starred.tsv. A story counts as read when all its articles are; when new coverage joins a
 * story you read, it shows as having new articles. Stars are remembered by the story's article ids, so a starred
 * story stays starred as it grows.
 */
public final class ReadState {
    /** A starred story: its article ids (when starred), headline and when. */
    public record Star(long time, Set<String> ids, String headline) {}

    private final Path readFile, starFile;
    private final Set<String> read = new HashSet<>();
    private final List<Star> stars = new ArrayList<>();
    private final Set<String> starredIds = new HashSet<>();
    private long readStamp = -1, starStamp = -1;

    public ReadState(Path dataDir) {
        this.readFile = dataDir.resolve("read.txt");
        this.starFile = dataDir.resolve("starred.tsv");
    }

    /** Reads both files (again, if another process changed them). */
    public synchronized ReadState load() {
        try {
            long rs = stamp(readFile);
            if (rs != readStamp) {
                read.clear();
                if (Files.exists(readFile)) for (String l : Files.readAllLines(readFile, StandardCharsets.UTF_8)) if (!l.isBlank()) read.add(l.strip());
                readStamp = rs;
            }
            long ss = stamp(starFile);
            if (ss != starStamp) {
                stars.clear();
                if (Files.exists(starFile)) {
                    for (String l : Files.readAllLines(starFile, StandardCharsets.UTF_8)) {
                        if (l.isBlank() || l.startsWith("#")) continue;
                        String[] p = l.split("\t", -1);
                        if (p.length < 2) continue;
                        try {
                            Set<String> ids = new LinkedHashSet<>();
                            for (String x : p[1].split(",")) if (!x.isBlank()) ids.add(x.strip());
                            stars.add(new Star(Long.parseLong(p[0]), ids, p.length > 2 ? p[2] : ""));
                        } catch (NumberFormatException ignored) { }
                    }
                }
                index();
                starStamp = ss;
            }
        } catch (IOException ignored) {
            // unreadable: start empty; nothing else depends on it
        }
        return this;
    }

    private static long stamp(Path p) throws IOException {
        return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() * 31 + Files.size(p) : 0;
    }

    private void index() {
        starredIds.clear();
        for (Star s : stars) starredIds.addAll(s.ids());
    }

    // ------------------------------------------------------------------ read

    public synchronized boolean isRead(Article a) { return read.contains(a.id); }

    /** Articles in the story you haven't read yet (0 = the whole story is read). */
    public synchronized int unreadCount(StoryCluster c) {
        int n = 0;
        for (Article a : c.articles) if (!read.contains(a.id)) n++;
        return n;
    }

    public synchronized boolean isUnread(StoryCluster c) { return unreadCount(c) > 0; }

    /** True when you read part of the story and new coverage has joined it since. */
    public synchronized boolean hasNewSinceRead(StoryCluster c) {
        int r = 0;
        for (Article a : c.articles) if (read.contains(a.id)) r++;
        return r > 0 && r < c.articles.size();
    }

    public synchronized void markRead(StoryCluster c) throws IOException { markRead(c.articles); }

    public synchronized void markRead(Collection<Article> articles) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Article a : articles) if (read.add(a.id)) sb.append(a.id).append('\n');
        if (sb.length() == 0) return;
        Files.createDirectories(readFile.getParent());
        Files.writeString(readFile, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        readStamp = stamp(readFile);
    }

    /** Marks the story unread again (forgets its articles as read). */
    public synchronized void markUnread(StoryCluster c) throws IOException {
        boolean changed = false;
        for (Article a : c.articles) changed |= read.remove(a.id);
        if (!changed) return;
        StringBuilder sb = new StringBuilder();
        for (String id : read) sb.append(id).append('\n');
        write(readFile, sb.toString());
        readStamp = stamp(readFile);
    }

    public synchronized int readCount() { return read.size(); }

    // ------------------------------------------------------------------ stars

    public synchronized boolean isStarred(StoryCluster c) {
        for (Article a : c.articles) if (starredIds.contains(a.id)) return true;
        return false;
    }

    public synchronized void star(StoryCluster c) throws IOException {
        if (isStarred(c)) return;
        Set<String> ids = new LinkedHashSet<>();
        for (Article a : c.articles) ids.add(a.id);
        stars.add(new Star(System.currentTimeMillis(), ids, c.headline));
        index();
        saveStars();
    }

    public synchronized void unstar(StoryCluster c) throws IOException {
        Set<String> ids = new HashSet<>();
        for (Article a : c.articles) ids.add(a.id);
        stars.removeIf(s -> !java.util.Collections.disjoint(s.ids(), ids));
        index();
        saveStars();
    }

    public synchronized List<Star> stars() { return List.copyOf(stars); }

    /** Every article id of every starred story (when starred). */
    public synchronized Set<String> starredArticleIds() { return Set.copyOf(starredIds); }

    private void saveStars() throws IOException {
        StringBuilder sb = new StringBuilder("# Starred stories: time\tarticle ids\theadline\n");
        for (Star s : stars) sb.append(s.time()).append('\t').append(String.join(",", s.ids())).append('\t')
                .append(s.headline().replace('\t', ' ').replace('\n', ' ')).append('\n');
        write(starFile, sb.toString());
        starStamp = stamp(starFile);
    }

    private static void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
