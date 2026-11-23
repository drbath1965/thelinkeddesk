package com.linkeddesk.app;

import com.linkeddesk.Check;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** AI-F06 (encrypted API key) and OPS-F04 (one window at a time). */
public final class AppTest {
    public static void run(Check c) throws Exception {
        c.suite("Encrypted API key (AI-F06)");
        Path home = Files.createTempDirectory("ld-secret");
        System.setProperty("linkeddesk.home", home.toString());
        SecretStore aes = new SecretStore(home.resolve(".secret.key"), false);
        String enc = aes.protect("sk-ant-api03-secret-value");
        c.that(enc.startsWith("aes:") && !enc.contains("secret-value"), "key is stored encrypted, not readable");
        c.eq("sk-ant-api03-secret-value", aes.unprotect(enc), "and decrypts back exactly");
        c.that(!enc.equals(aes.protect("sk-ant-api03-secret-value")), "each save uses a fresh random IV");
        SecretStore otherKey = new SecretStore(Files.createTempDirectory("ld-other").resolve(".secret.key"), false);
        boolean refused;
        try { otherKey.unprotect(enc); refused = false; } catch (Exception e) { refused = true; }
        c.that(refused, "another install's key can't decrypt it");

        Settings.useSecretStore(aes);
        Files.writeString(home.resolve("settings.properties"), "dailyTime=06\\:30\nanthropicApiKey=sk-ant-plaintext-from-v1\n");
        Settings s = Settings.load();
        c.eq("sk-ant-plaintext-from-v1", s.apiKey(), "a plain-text key from v1.0 still works");
        String file = Files.readString(home.resolve("settings.properties"), StandardCharsets.ISO_8859_1);
        c.that(!file.contains("plaintext-from-v1") && file.contains("anthropicApiKey.enc=aes\\:"), "and is converted to encrypted on first use");
        c.eq("sk-ant-plaintext-from-v1", Settings.load().apiKey(), "reads back after restart");
        s.setApiKey("");
        s.save();
        c.that(!Settings.load().hasApiKey(), "clearing the key removes it");

        c.suite("One window at a time (OPS-F04)");
        Path win = Files.createTempDirectory("ld-window");
        Process first = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                "com.linkeddesk.app.AppTest$FirstWindow", win.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String ready = new String(first.getInputStream().readNBytes(5), StandardCharsets.US_ASCII);
        boolean secondIsFirst = SingleInstance.claim(win, () -> { });
        String shown = new String(first.getInputStream().readNBytes(4), StandardCharsets.US_ASCII);
        first.destroy();
        first.waitFor();
        c.that("ready".equals(ready) && !secondIsFirst, "a second launch sees the open window");
        c.eq("show", shown, "and asks it to come to the front");
        Thread.sleep(300);
        c.that(SingleInstance.claim(win, () -> { }), "after the window closes, the app can open again");
        SingleInstance.release();
    }

    /** Child process standing in for an open window. */
    public static final class FirstWindow {
        public static void main(String[] a) throws Exception {
            Object done = new Object();
            SingleInstance.claim(Path.of(a[0]), () -> { System.out.print("show"); System.out.flush(); });
            System.out.print("ready");
            System.out.flush();
            synchronized (done) { done.wait(60_000); }
        }
    }
}
