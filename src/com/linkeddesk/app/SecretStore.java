package com.linkeddesk.app;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Encrypts secrets (the Anthropic API key) before they are written to settings.properties.
 *
 *  - Windows: DPAPI for the current user (via the built-in PowerShell), so only your Windows account on
 *    this PC can decrypt it. Stored as "dpapi:&lt;base64&gt;".
 *  - Otherwise, or if DPAPI is unavailable: AES-256-GCM with a random key kept in .secret.key (readable
 *    only by you where the file system allows). Stored as "aes:&lt;base64&gt;". This keeps the key out of
 *    the settings file but is weaker than DPAPI, because the key file sits next to it.
 */
public final class SecretStore {
    private final Path keyFile;
    private final boolean useDpapi;

    public SecretStore() {
        this(AppPaths.home().resolve(".secret.key"),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        && !"aes".equals(System.getProperty("linkeddesk.secrets")));
    }

    public SecretStore(Path keyFile, boolean useDpapi) {
        this.keyFile = keyFile;
        this.useDpapi = useDpapi;
    }

    public String protect(String secret) throws Exception {
        if (secret == null || secret.isEmpty()) return "";
        if (useDpapi) {
            try { return "dpapi:" + powershell(PROTECT, secret); }
            catch (Exception e) { Logs.error("DPAPI unavailable, using AES file key instead", e); }
        }
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        return "aes:" + Base64.getEncoder().encodeToString(all);
    }

    public String unprotect(String stored) throws Exception {
        if (stored == null || stored.isEmpty()) return "";
        if (stored.startsWith("dpapi:")) return powershell(UNPROTECT, stored.substring(6));
        if (stored.startsWith("aes:")) {
            byte[] all = Base64.getDecoder().decode(stored.substring(4));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unknown secret format");
    }

    private byte[] key() throws IOException {
        if (Files.exists(keyFile)) return Base64.getDecoder().decode(Files.readString(keyFile).trim());
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(k));
        try { Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Windows: user profile folders are private by default */ }
        return k;
    }

    // The secret goes in on standard input, never on the command line, so it doesn't show in process lists.
    private static final String PROTECT = "Add-Type -AssemblyName System.Security; $s=[Console]::In.ReadToEnd().Trim(); "
            + "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($s),$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser))";
    private static final String UNPROTECT = "Add-Type -AssemblyName System.Security; $s=[Console]::In.ReadToEnd().Trim(); "
            + "[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($s),$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser))";

    private static String powershell(String script, String input) throws Exception {
        Process p = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script))
                .redirectErrorStream(false).start();
        try (OutputStream in = p.getOutputStream()) { in.write(input.getBytes(StandardCharsets.UTF_8)); }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) {
            throw new IllegalStateException("PowerShell DPAPI failed: " + (err.isEmpty() ? "exit " + p.exitValue() : err));
        }
        return out;
    }
}
