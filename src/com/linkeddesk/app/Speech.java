package com.linkeddesk.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read aloud (UI-F20) with the voice built into the computer: Windows' speech synthesizer (through the built-in
 * PowerShell, text passed on standard input so nothing needs escaping), "say" on a Mac, or espeak on Linux if it's
 * installed. One reading at a time; {@link #stop()} ends it.
 */
public final class Speech {
    private Speech() {}

    private static Process current;

    public static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }
    static boolean mac() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); }

    /** Text cleaned for reading: no citation brackets, link arrows, "more ›" or extra spaces. */
    public static String clean(String text) {
        return text.replaceAll("\\[[^\\]\\n]{1,60}\\]", " ").replace("more ›", " ").replace("↗", " ").replace("·", ",")
                .replaceAll("(?m)^\\s*[•*-]\\s+", "").replaceAll("[ \\t]+", " ").replaceAll(" +([.,;:!?])", "$1").replaceAll("([,;:])\\1+", "$1").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** The command that reads standard input aloud (rate -5..5, voice name or blank for the default). */
    public static List<String> command(String voice, int rate) {
        int r = Math.max(-5, Math.min(5, rate));
        if (windows()) {
            String select = voice == null || voice.isBlank() ? "" : "try { $s.SelectVoice('" + voice.replace("'", "''") + "') } catch {} ; ";
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " + select
                            + "$s.Rate = " + (r * 2) + "; $t = [Console]::In.ReadToEnd(); $s.Speak($t)");
        }
        if (mac()) return List.of("say", "-r", Integer.toString(175 + r * 25));
        return List.of("espeak", "-s", Integer.toString(160 + r * 20));
    }

    /** Starts reading {@code text} aloud (stopping any reading in progress). */
    public static synchronized void speak(String text, String voice, int rate) throws IOException {
        stop();
        String t = clean(text);
        if (t.isEmpty()) return;
        Process p;
        try { p = new ProcessBuilder(command(voice, rate)).redirectErrorStream(true).start(); }
        catch (IOException e) {
            throw new IOException(windows() ? "Windows speech isn't available: " + e.getMessage()
                    : "Reading aloud needs a speech program (\"say\" on a Mac, espeak on Linux): " + e.getMessage());
        }
        current = p;
        Thread w = new Thread(() -> {
            try (OutputStream out = p.getOutputStream()) { out.write(t.getBytes(windows() ? java.nio.charset.Charset.defaultCharset() : StandardCharsets.UTF_8)); }
            catch (IOException ignored) { /* stopped before it finished writing */ }
            try { p.getInputStream().transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) { }
        }, "read aloud");
        w.setDaemon(true);
        w.start();
    }

    public static synchronized boolean speaking() { return current != null && current.isAlive(); }

    public static synchronized void stop() {
        if (current != null && current.isAlive()) { current.descendants().forEach(ProcessHandle::destroy); current.destroy(); }
        current = null;
    }

    /** The installed voices (Windows only; empty elsewhere or if they can't be listed). */
    public static List<String> voices() {
        List<String> out = new ArrayList<>();
        if (!windows()) return out;
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | % { $_.VoiceInfo.Name }")
                    .redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor();
            for (String l : s.split("\\R")) if (!l.isBlank()) out.add(l.strip());
        } catch (Exception e) { Logs.error("Listing voices", e); }
        return out;
    }
}
