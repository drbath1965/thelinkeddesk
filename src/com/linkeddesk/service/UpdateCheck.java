package com.linkeddesk.service;

import com.linkeddesk.app.AppPaths;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Updates from GitHub releases (OPS-F07). Asks GitHub for the newest release of the app's repository (at most once
 * a day when automatic), compares versions, and can download the release's LinkedDesk-x.y.z.jar next to the current
 * one, check it is a real copy of the app, start it and let this copy close. The daily task then follows the new copy
 * (see WindowsTask.followThisCopy). Nothing is installed or replaced: the old jar stays where it was.
 * The address can be changed with -Dlinkeddesk.updateUrl=… (used by the tests).
 */
public final class UpdateCheck {
    public static final String REPO = "drbath1965/thelinkeddesk";
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String PAGE = "https://github.com/" + REPO + "/releases";
    public static final long DAY = 86_400_000L;

    public record Release(String version, String name, String notes, String pageUrl, String jarUrl, String jarName, long jarSize) {
        public boolean hasJar() { return jarUrl != null && !jarUrl.isBlank(); }
    }

    private final String url;
    private final HttpClient http;

    public UpdateCheck() { this(System.getProperty("linkeddesk.updateUrl", API)); }
    public UpdateCheck(String url) {
        this.url = url;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** True if an automatic check is due (on, and the last one was over a day ago). */
    public static boolean due(boolean enabled, long lastCheck, long now) { return enabled && now - lastCheck >= DAY; }

    /** Compares dotted versions numerically ("1.10.0" &gt; "1.9.2"); a leading v and any -suffix are ignored. */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] parts(String v) {
        String s = v == null ? "" : v.trim().replaceFirst("^[vV]", "");
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] p = s.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { out[i] = Integer.parseInt(p[i].replaceAll("\\D.*", "")); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    /** The newest release, or null if the repository has none yet. */
    public Release latest() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "TheLinkedDesk/" + AppPaths.VERSION).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() == 403 || r.statusCode() == 429) throw new IOException("GitHub is limiting requests right now; try again later.");
        if (r.statusCode() / 100 != 2) throw new IOException("GitHub answered " + r.statusCode() + ".");
        return parse(r.body());
    }

    /** Reads GitHub's release JSON: tag, name, notes, page, and the LinkedDesk*.jar asset. */
    @SuppressWarnings("unchecked")
    public static Release parse(String json) {
        Object o = new Json(json).value();
        if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("Not a release");
        String tag = str(m.get("tag_name"));
        if (tag.isBlank()) throw new IllegalArgumentException("The release has no version tag");
        String jarUrl = null, jarName = null;
        long size = 0;
        if (m.get("assets") instanceof List<?> assets) for (Object a : assets) {
            if (!(a instanceof Map<?, ?> am)) continue;
            String n = str(am.get("name"));
            if (n.toLowerCase(Locale.ROOT).startsWith("linkeddesk") && n.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jarName = n;
                jarUrl = str(am.get("browser_download_url"));
                size = am.get("size") instanceof Number num ? num.longValue() : 0;
                break;
            }
        }
        String page = str(m.get("html_url"));
        return new Release(tag.replaceFirst("^[vV]", ""), str(m.get("name")), str(m.get("body")), page.isBlank() ? PAGE : page, jarUrl, jarName, size);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /**
     * Downloads the release's jar into {@code dir} as LinkedDesk-&lt;version&gt;.jar (via a .part file), checks it,
     * and returns its path. An existing good copy is reused.
     */
    public Path download(Release r, Path dir, LongConsumer progress) throws IOException, InterruptedException {
        if (!r.hasJar()) throw new IOException("This release has no LinkedDesk jar attached; download it from " + r.pageUrl());
        Path target = dir.resolve("LinkedDesk-" + r.version() + ".jar");
        if (Files.exists(target)) {
            try { validate(target, r); return target; } catch (IOException bad) { Files.delete(target); }
        }
        Path part = dir.resolve(target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(r.jarUrl())).timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream").header("User-Agent", "TheLinkedDesk/" + AppPaths.VERSION).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) throw new IOException("The download failed (" + resp.statusCode() + ").");
        try (InputStream in = resp.body(); var out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); total += n; if (progress != null) progress.accept(total); }
        }
        try { validate(part, r); }
        catch (IOException e) { Files.deleteIfExists(part); throw e; }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** The jar must be the expected size, open as a jar, and be this app at the release's version. */
    public static void validate(Path jar, Release r) throws IOException {
        if (r.jarSize() > 0 && Files.size(jar) != r.jarSize())
            throw new IOException("The download is incomplete (" + Files.size(jar) + " of " + r.jarSize() + " bytes).");
        try (JarFile j = new JarFile(jar.toFile(), true)) {
            Manifest m = j.getManifest();
            String main = m == null ? null : m.getMainAttributes().getValue("Main-Class");
            if (!"com.linkeddesk.app.App".equals(main) || j.getEntry("com/linkeddesk/app/App.class") == null)
                throw new IOException("The download isn't The Linked Desk.");
            String v = m.getMainAttributes().getValue("Implementation-Version");
            if (v != null && compare(v, r.version()) != 0) throw new IOException("The download is version " + v + ", not " + r.version() + ".");
            var e = j.getEntry("com/linkeddesk/app/AppPaths.class");
            if (e != null) try (InputStream in = j.getInputStream(e)) { in.readAllBytes(); }   // checks the signature/CRC path reads
        } catch (java.util.zip.ZipException e) {
            throw new IOException("The download isn't a valid jar: " + e.getMessage());
        }
    }

    /** Can this copy update itself in place? Only when it runs from a jar in a folder it can write to. */
    public static Path updatableFolder(Path runningFrom) {
        if (System.getProperty("jpackage.app-path") != null) return null;            // installed app: use the installer
        if (runningFrom == null || !runningFrom.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
        Path dir = runningFrom.toAbsolutePath().getParent();
        return dir != null && Files.isWritable(dir) ? dir : null;
    }

    /** Starts the new jar; it waits for this window to close before opening (--after-update). */
    public static void launch(Path jar, String javaw) throws IOException {
        new ProcessBuilder(javaw, "-jar", jar.toAbsolutePath().toString(), "--after-update")
                .directory(jar.toAbsolutePath().getParent().toFile()).start();
    }

    /** A small JSON reader: objects become maps, arrays lists, numbers Double/Long. Enough for GitHub's answers. */
    static final class Json {
        private final String s;
        private int i;
        Json(String s) { this.s = s; }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws(); String k = string(); ws(); expect(':');
                    m.put(k, value()); ws();
                    if (peek(',')) { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (peek(',')) { i++; continue; }
                    expect(']'); return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Bad JSON at " + i);
            String n = s.substring(st, i);
            return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        private void expect(char c) { if (!peek(c)) throw new IllegalArgumentException("Expected " + c + " at " + i); i++; }
    }
}
