package com.linkeddesk.ai;

import com.linkeddesk.Check;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** AI-F01: the "Ask Claude why" call, against a local stand-in for the Anthropic API. */
public final class ClaudeClientTest {
    public static void run(Check c) throws Exception {
        c.suite("Ask Claude why (AI-F01)");
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seen.put("method", ex.getRequestMethod());
            seen.put("key", String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")));
            seen.put("version", String.valueOf(ex.getRequestHeaders().getFirst("anthropic-version")));
            seen.put("type", String.valueOf(ex.getRequestHeaders().getFirst("content-type")));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.put("body", body);
            String key = seen.get("key");
            int status;
            String reply;
            switch (key) {
                case "good" -> {
                    status = 200;
                    reply = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"Real link: both stories turn on Nvidia\\u2019s leadership.\\nReported: Huang resigned on Nov 12. \\\"Watch\\\" the next guidance.\"}],"
                            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":512,\"output_tokens\":64}}";
                }
                case "bad" -> { status = 401; reply = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"; }
                case "busy" -> { status = 529; reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"; }
                case "nomodel" -> { status = 404; reply = "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-nope\"}}"; }
                default -> { status = 500; reply = "{}"; }
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        System.setProperty("linkeddesk.apiBase", "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            StoryLink link = sampleLink();
            String answer = new ClaudeClient("good", "claude-sonnet-5-5").explain(link);
            c.eq("POST", seen.get("method"), "sends a POST to /v1/messages");
            c.eq("good", seen.get("key"), "sends the API key header");
            c.eq("2023-06-01", seen.get("version"), "sends the anthropic-version header");
            c.eq("application/json", seen.get("type"), "sends JSON");
            String body = seen.get("body");
            c.that(body.startsWith("{\"model\":\"claude-sonnet-5-5\",\"max_tokens\":700,\"messages\":[{\"role\":\"user\",\"content\":\""), "body has model, max_tokens and one user message");
            c.that(body.contains("Nvidia shares plunge after CEO Jensen Huang resigns") && body.contains("Anthropic opens 34% above IPO price"), "prompt includes both stories' headlines");
            c.that(body.contains("share these terms: Nvidia, Huang"), "prompt includes the shared terms");
            c.that(body.contains("\\\"quoted\\\"") && body.contains("\\n"), "quotes and line breaks are escaped as valid JSON");
            c.that(isValidJson(body), "request body is valid JSON");
            c.eq("Real link: both stories turn on Nvidia’s leadership.\nReported: Huang resigned on Nov 12. \"Watch\" the next guidance.", answer,
                    "answer text decoded (unicode, quotes, line breaks)");

            c.that(msg("bad").startsWith("The API key was not accepted. Check the key in Settings."), "401 explained: bad API key");
            c.that(msg("bad").contains("invalid x-api-key"), "Anthropic's own detail is kept");
            c.that(msg("busy").startsWith("Claude is overloaded right now."), "529 explained: overloaded");
            c.that(msg("nomodel").contains("wasn't found. Check the model name"), "404 explained: model name");
            c.that(msg("other").startsWith("Anthropic's service had a problem."), "500 explained: service problem");
        } finally {
            server.stop(0);
        }
        System.setProperty("linkeddesk.apiBase", "http://127.0.0.1:9");
        c.that(msg("good").startsWith("Couldn't reach Anthropic."), "no connection explained: check internet");
        System.clearProperty("linkeddesk.apiBase");
    }

    private static String msg(String key) {
        try {
            new ClaudeClient(key, key.equals("nomodel") ? "claude-nope" : "claude-sonnet-5-5").explain(sampleLink());
            return "(no error)";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static StoryLink sampleLink() {
        long t = 1_795_000_000_000L;
        StoryCluster a = new StoryCluster("a");
        a.add(new Article("1", "https://x/1", "Nvidia shares plunge after CEO Jensen Huang resigns", "Huang resigned; \"quoted\" line\nsecond line", "Fox Business", Category.FINANCE, t, t));
        a.category = Category.FINANCE;
        StoryCluster b = new StoryCluster("b");
        b.add(new Article("2", "https://x/2", "Anthropic opens 34% above IPO price", "Largest IPO on record.", "CNBC", Category.FINANCE, t + 86_400_000L, t));
        b.category = Category.FINANCE;
        return new StoryLink(a, b, 0.2, List.of("Nvidia", "Huang"));
    }

    /** Validates JSON with the JDK's JavaScript-free approach: a small recursive-descent check. */
    private static boolean isValidJson(String s) {
        int[] i = {0};
        try { value(s, i); ws(s, i); return i[0] == s.length(); } catch (RuntimeException e) { return false; }
    }
    private static void ws(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
    private static void value(String s, int[] i) {
        ws(s, i);
        char ch = s.charAt(i[0]);
        if (ch == '{') { i[0]++; ws(s, i); if (s.charAt(i[0]) == '}') { i[0]++; return; }
            while (true) { ws(s, i); str(s, i); ws(s, i); expect(s, i, ':'); value(s, i); ws(s, i); if (s.charAt(i[0]) == ',') { i[0]++; continue; } expect(s, i, '}'); return; } }
        if (ch == '[') { i[0]++; ws(s, i); if (s.charAt(i[0]) == ']') { i[0]++; return; }
            while (true) { value(s, i); ws(s, i); if (s.charAt(i[0]) == ',') { i[0]++; continue; } expect(s, i, ']'); return; } }
        if (ch == '"') { str(s, i); return; }
        int st = i[0];
        while (i[0] < s.length() && "-+.eE0123456789truefalsn".indexOf(s.charAt(i[0])) >= 0) i[0]++;
        if (st == i[0]) throw new IllegalStateException("bad value");
    }
    private static void str(String s, int[] i) {
        expect(s, i, '"');
        while (true) {
            char ch = s.charAt(i[0]++);
            if (ch == '"') return;
            if (ch < 0x20) throw new IllegalStateException("raw control char");
            if (ch == '\\') { char e = s.charAt(i[0]++); if (e == 'u') i[0] += 4; else if ("\"\\/bfnrt".indexOf(e) < 0) throw new IllegalStateException("bad escape"); }
        }
    }
    private static void expect(String s, int[] i, char ch) { if (s.charAt(i[0]) != ch) throw new IllegalStateException("expected " + ch); i[0]++; }
}
