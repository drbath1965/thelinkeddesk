package com.linkeddesk.store;

import com.linkeddesk.app.AppPaths;
import com.linkeddesk.app.Logs;
import com.linkeddesk.app.SafeFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Ids of articles added by filling in past days, so they can be marked "filled in" (FET-F12). */
public final class FilledIn {
    private final Path file;
    private final Set<String> ids = new HashSet<>();
    private boolean loaded;

    public FilledIn() { this(AppPaths.home().resolve("filled-in.txt")); }
    public FilledIn(Path file) { this.file = file; }

    private synchronized void ensure() {
        if (loaded) return;
        loaded = true;
        try { for (String l : SafeFiles.readLines(file)) if (!l.isBlank()) ids.add(l.strip()); }
        catch (IOException e) { Logs.error("Reading the filled-in list", e); }
    }

    public synchronized boolean contains(String id) { ensure(); return ids.contains(id); }
    public synchronized int size() { ensure(); return ids.size(); }

    public synchronized void addAll(Collection<String> more) throws IOException {
        ensure();
        if (!ids.addAll(more)) return;
        SafeFiles.write(file, String.join("\n", ids) + "\n");
    }
}
