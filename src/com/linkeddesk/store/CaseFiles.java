package com.linkeddesk.store;

import com.linkeddesk.app.SafeFiles;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Case files (UI-F19): named folders of stories with your own notes, kept in casefiles.txt next to feeds.txt (and so
 * in backups). Stories are kept by their article ids, headline, link and date, so a case file still opens after the
 * news has moved on. Lines: CASE id, created, name, note | STORY case id, added, ids, headline, link, first published, note.
 */
public final class CaseFiles {
    public record Item(long added, Set<String> ids, String headline, String url, long published, String note) {
        Item withNote(String n) { return new Item(added, ids, headline, url, published, n); }
    }

    public record CaseFile(String id, long created, String name, String note, List<Item> items) {}

    private final Path file;
    private final Map<String, CaseFile> cases = new LinkedHashMap<>();
    private boolean unreadable;

    public CaseFiles(Path file) { this.file = file; }

    public synchronized CaseFiles load() {
        List<String> lines;
        try { lines = SafeFiles.readLines(file); unreadable = false; }
        catch (SafeFiles.Unreadable u) { unreadable = true; return this; }
        cases.clear();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] p = l.split("\t", -1);
            try {
                if (p[0].equals("CASE") && p.length >= 5)
                    cases.put(p[1], new CaseFile(p[1], Long.parseLong(p[2]), unesc(p[3]), unesc(p[4]), new ArrayList<>()));
                else if (p[0].equals("STORY") && p.length >= 8 && cases.containsKey(p[1])) {
                    Set<String> ids = new LinkedHashSet<>();
                    for (String x : p[3].split(",")) if (!x.isBlank()) ids.add(x);
                    cases.get(p[1]).items().add(new Item(Long.parseLong(p[2]), ids, unesc(p[4]), unesc(p[5]), Long.parseLong(p[6]), unesc(p[7])));
                }
            } catch (NumberFormatException ignored) { /* a damaged line: skip it */ }
        }
        return this;
    }

    public synchronized List<CaseFile> all() {
        List<CaseFile> out = new ArrayList<>();
        for (CaseFile c : cases.values()) out.add(new CaseFile(c.id(), c.created(), c.name(), c.note(), List.copyOf(c.items())));
        return out;
    }

    public synchronized CaseFile get(String id) {
        CaseFile c = cases.get(id);
        return c == null ? null : new CaseFile(c.id(), c.created(), c.name(), c.note(), List.copyOf(c.items()));
    }

    public synchronized CaseFile create(String name) throws IOException {
        load();
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) throw new IllegalArgumentException("Give the case file a name.");
        for (CaseFile c : cases.values()) if (c.name().equalsIgnoreCase(n)) throw new IllegalArgumentException("There's already a case file called \"" + c.name() + "\".");
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(cases.size(), 36);
        CaseFile c = new CaseFile(id, System.currentTimeMillis(), n, "", new ArrayList<>());
        cases.put(id, c);
        save();
        return get(id);
    }

    public synchronized void rename(String id, String name) throws IOException {
        load();
        CaseFile c = cases.get(id);
        if (c == null || name == null || name.isBlank()) return;
        cases.put(id, new CaseFile(id, c.created(), name.strip(), c.note(), c.items()));
        save();
    }

    public synchronized void delete(String id) throws IOException { load(); if (cases.remove(id) != null) save(); }

    public synchronized void setNote(String id, String note) throws IOException {
        load();
        CaseFile c = cases.get(id);
        if (c == null) return;
        cases.put(id, new CaseFile(id, c.created(), c.name(), note == null ? "" : note, c.items()));
        save();
    }

    /** Adds a story (false if it's already in the case file). */
    public synchronized boolean add(String id, StoryCluster s) throws IOException {
        load();
        CaseFile c = cases.get(id);
        if (c == null) throw new IllegalArgumentException("That case file no longer exists.");
        Set<String> ids = ConnectionStore.ids(s);
        for (Item it : c.items()) for (String x : ids) if (it.ids().contains(x)) return false;
        Article lead = null;
        for (Article a : s.articles) if (lead == null || a.published < lead.published) lead = a;
        c.items().add(new Item(System.currentTimeMillis(), ids, s.headline, lead == null ? "" : lead.url, lead == null ? 0 : lead.published, ""));
        save();
        return true;
    }

    public synchronized void remove(String id, Item item) throws IOException {
        load();
        CaseFile c = cases.get(id);
        if (c != null && c.items().removeIf(i -> i.ids().equals(item.ids()))) save();
    }

    public synchronized void setItemNote(String id, Item item, String note) throws IOException {
        load();
        CaseFile c = cases.get(id);
        if (c == null) return;
        for (int i = 0; i < c.items().size(); i++) if (c.items().get(i).ids().equals(item.ids())) c.items().set(i, c.items().get(i).withNote(note == null ? "" : note));
        save();
    }

    /** The case files a story is in. */
    public synchronized List<CaseFile> containing(StoryCluster s) {
        Set<String> mine = new HashSet<>();
        for (Article a : s.articles) mine.add(a.id);
        List<CaseFile> out = new ArrayList<>();
        for (CaseFile c : cases.values())
            for (Item it : c.items()) if (!Collections.disjoint(it.ids(), mine)) { out.add(c); break; }
        return out;
    }

    private void save() throws IOException {
        SafeFiles.checkSafeToSave(unreadable, file);
        StringBuilder sb = new StringBuilder("# The Linked Desk case files\n");
        for (CaseFile c : cases.values()) {
            sb.append("CASE\t").append(c.id()).append('\t').append(c.created()).append('\t').append(esc(c.name())).append('\t').append(esc(c.note())).append('\n');
            for (Item it : c.items())
                sb.append("STORY\t").append(c.id()).append('\t').append(it.added()).append('\t').append(String.join(",", it.ids())).append('\t')
                  .append(esc(it.headline())).append('\t').append(esc(it.url())).append('\t').append(it.published()).append('\t').append(esc(it.note())).append('\n');
        }
        SafeFiles.write(file, sb.toString());
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n"); }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { char n = s.charAt(++i); sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n); }
            else sb.append(c);
        }
        return sb.toString();
    }
}
