package com.linkeddesk.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the app keeps its data: %USERPROFILE%\LinkedDesk by default. */
public final class AppPaths {
    private AppPaths() {}

    public static Path home() {
        String override = System.getProperty("linkeddesk.home", System.getenv("LINKEDDESK_HOME"));
        Path p = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), "LinkedDesk");
        try {
            Files.createDirectories(p.resolve("data"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data folder " + p, e);
        }
        return p;
    }

    /** The version shown in the title bar and written into backups. */
    public static final String VERSION = "1.7";

    public static Path dataDir() { return home().resolve("data"); }
    public static Path feedsFile() { return home().resolve("feeds.txt"); }
    public static Path settingsFile() { return home().resolve("settings.properties"); }
    public static Path logFile() { return home().resolve("fetch.log"); }
    public static Path aliasesFile() { return home().resolve("aliases.txt"); }
    public static Path stopwordsFile() { return home().resolve("stopwords.txt"); }
    public static Path feedbackFile() { return home().resolve("link-feedback.tsv"); }
    public static Path deskRulesFile() { return home().resolve("desk-rules.tsv"); }
    public static Path regionsFile() { return home().resolve("regions.txt"); }
    public static Path calibrationFile() { return home().resolve("calibration.properties"); }
    public static Path lockFile() { return home().resolve(".fetch.lock"); }
}
