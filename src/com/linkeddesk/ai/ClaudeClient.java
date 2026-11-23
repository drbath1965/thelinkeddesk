package com.linkeddesk.ai;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Optional: asks Claude (Anthropic API) whether a link between two stories is real and why.
 * Needs an API key from console.anthropic.com entered in Settings. Uses only the JDK.
 */
public final class ClaudeClient {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public String explain(StoryLink link) throws Exception { return ask(explainPrompt(link)); }

    /** The "Ask Claude why" question for a link. */
    public static String explainPrompt(StoryLink link) {
        return """
                You are the analyst for a world-news desk. Two news stories were linked automatically because they \
                share these terms: %s.

                STORY A (%s desk)
                %s

                STORY B (%s desk)
                %s

                Using only the reporting above, say whether the link is real (causal, contextual, same actors) or \
                coincidental. Explain the connection in under 150 words, separate what the reports say from your own \
                inference, and name one thing to watch next. Plain text, no headings."""
                .formatted(String.join(", ", link.sharedTerms()),
                        link.from().category.label(), describe(link.from()),
                        link.to().category.label(), describe(link.to()));
    }

    private static String describe(StoryCluster c) { return describe(c, 6, 300); }

    /** The story's coverage as prompt lines: outlet, date, headline and the start of the summary. */
    static String describe(StoryCluster c, int maxArticles, int summaryChars) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Article a : c.articlesNewestFirst()) {
            if (n++ == maxArticles) break;
            sb.append("- ").append(a.source).append(", ").append(DAY.format(Instant.ofEpochMilli(a.published)))
              .append(": ").append(a.title);
            if (!a.summary.isEmpty()) {
                String s = a.summary.length() > summaryChars ? a.summary.substring(0, summaryChars) + "…" : a.summary;
                sb.append(" — ").append(s);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Claude's answer and the tokens it used (for the monthly budget). */
    public record Reply(String text, long inputTokens, long outputTokens) {}

    public String ask(String prompt) throws Exception { return send(prompt, 700).text(); }

    public String model() { return model; }

    public Reply send(String prompt, int maxTokens) throws Exception {
        String body = "{\"model\":" + json(model) + ",\"max_tokens\":" + maxTokens + ","
                + "\"messages\":[{\"role\":\"user\",\"content\":" + json(prompt) + "}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase() + "/v1/messages"))
                .timeout(Duration.ofSeconds(90))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("Claude took too long to answer. Try again in a minute.", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Couldn't reach Anthropic. Check your internet connection and try again.", e);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(friendlyError(resp.statusCode(), extractString(resp.body(), "\"message\"")));
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        String r = resp.body();
        while (true) {
            int i = r.indexOf("\"text\"", from);
            if (i < 0) break;
            int c = i + 6;
            while (c < r.length() && Character.isWhitespace(r.charAt(c))) c++;
            if (c < r.length() && r.charAt(c) == ':') {
                String t = extractString(r.substring(i), "\"text\"");
                if (t != null) out.append(t);
            }
            from = i + 6;
        }
        return new Reply(out.length() == 0 ? "(Claude returned no text.)" : out.toString().trim(),
                number(r, "\"input_tokens\""), number(r, "\"output_tokens\""));
    }

    /** Reads the number that follows {@code key}, or 0. */
    static long number(String src, String key) {
        int k = src.indexOf(key);
        if (k < 0) return 0;
        int c = src.indexOf(':', k + key.length());
        if (c < 0) return 0;
        int i = c + 1;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        int j = i;
        while (j < src.length() && Character.isDigit(src.charAt(j))) j++;
        return j > i ? Long.parseLong(src.substring(i, j)) : 0;
    }

    /** Plain-language explanation of an API error, with Anthropic's own message for detail. */
    String friendlyError(int status, String apiMessage) {
        String what = switch (status) {
            case 401 -> "The API key was not accepted. Check the key in Settings.";
            case 403 -> "This API key isn't allowed to use that model or feature.";
            case 404 -> "The model \"" + model + "\" wasn't found. Check the model name in Settings.";
            case 400 -> "The request was rejected. If the message mentions the model, check the model name in Settings.";
            case 402 -> "The Anthropic account has no credit left. Add credit at console.anthropic.com.";
            case 413 -> "These stories were too long to send in one request.";
            case 429 -> "Too many requests right now. Wait a minute and try again.";
            case 500, 502, 503 -> "Anthropic's service had a problem. Try again in a few minutes.";
            case 529 -> "Claude is overloaded right now. Try again in a few minutes.";
            default -> "Claude couldn't answer (error " + status + ").";
        };
        return apiMessage == null || apiMessage.isBlank() ? what : what + "\n\nDetails: " + apiMessage;
    }

    /** API address; overridable with -Dlinkeddesk.apiBase for testing. */
    private static String apiBase() {
        String b = System.getProperty("linkeddesk.apiBase", "https://api.anthropic.com");
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    // ---------------------------------------------------------------- minimal JSON helpers

    /** JSON string escaping (for test stand-ins of the API). */
    public static String jsonForTests(String s) { return json(s); }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Reads the JSON string value that follows {@code key} (e.g. "\"text\""). */
    static String extractString(String src, String key) {
        int k = -1, colon = -1;
        for (int from = 0; ; from = k + 1) {
            k = src.indexOf(key, from);
            if (k < 0) return null;
            int c = k + key.length();
            while (c < src.length() && Character.isWhitespace(src.charAt(c))) c++;
            if (c < src.length() && src.charAt(c) == ':') { colon = c; break; }
        }
        int q = colon + 1;
        while (q < src.length() && Character.isWhitespace(src.charAt(q))) q++;
        if (q >= src.length() || src.charAt(q) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') return sb.toString();
            if (c == '\\' && i + 1 < src.length()) {
                char n = src.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}
