/*
    Local LLM client for bot dialogue.

    Talks to an Ollama instance over its HTTP API. Two properties matter more than anything
    else here and drive most of the design:

      1. Nothing may block a Netty event loop thread. Chat handlers run on those threads and a
         model call takes seconds, so every call is asynchronous and the caller gets a future.
      2. A missing or slow model must never mean a silent bot. Every failure path resolves to
         an empty Optional so the caller can fall back to a canned line.

    Deliberately dependency-free: the server ships no JSON library, so request encoding and
    the single field this needs from the response are handled here rather than by pulling in
    Jackson for two string operations.
*/
package server.bot;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static volatile LlmClient instance;

    private final HttpClient http;

    /**
     * Character ids with a request already in flight. A bot that is being spammed should drop
     * the extra prompts rather than queue them - queued replies arrive after the conversation
     * has moved on, and they multiply load for no benefit.
     */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();

    private LlmClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static LlmClient getInstance() {
        LlmClient cached = instance;
        if (cached != null) {
            return cached;
        }
        synchronized (LlmClient.class) {
            if (instance == null) {
                instance = new LlmClient();
            }
            return instance;
        }
    }

    public static boolean isEnabled() {
        return YamlConfig.config.server.USE_BOT_LLM;
    }

    /**
     * Asks the model for a reply, in character.
     *
     * @param botId         character id, used only to bound concurrent requests per bot
     * @param systemPrompt  persona, goal and any stable facts the reply must stay consistent with
     * @param userMessage   what was said to the bot
     * @return a future that always completes: the reply, or empty on disabled/busy/error/timeout
     */
    public CompletableFuture<Optional<String>> chat(int botId, String systemPrompt, String userMessage) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (!inFlight.add(botId)) {
            log.debug("Dropping LLM prompt for chr {}: request already in flight", botId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final int timeoutMs = YamlConfig.config.server.BOT_LLM_TIMEOUT_MS;
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(YamlConfig.config.server.BOT_LLM_ENDPOINT + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(systemPrompt, userMessage)))
                    .build();
        } catch (RuntimeException e) {
            // Malformed endpoint in config - do not let it propagate into a chat handler.
            inFlight.remove(botId);
            log.warn("Bad BOT_LLM_ENDPOINT '{}': {}", YamlConfig.config.server.BOT_LLM_ENDPOINT, e.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    inFlight.remove(botId);
                    if (error != null) {
                        log.debug("LLM call failed for chr {}: {}", botId, error.toString());
                        return Optional.<String>empty();
                    }
                    if (response.statusCode() != 200) {
                        log.debug("LLM returned HTTP {} for chr {}", response.statusCode(), botId);
                        return Optional.<String>empty();
                    }
                    return extractContent(response.body()).map(LlmClient::tidyForChat);
                });
    }

    private static String buildRequestBody(String systemPrompt, String userMessage) {
        // stream=false so the whole reply arrives as one JSON object rather than NDJSON chunks.
        // num_predict caps generation server-side: a bot that monologues cannot be sent as chat
        // anyway, and a shorter cap is also a faster response.
        return "{"
                + "\"model\":\"" + jsonEscape(YamlConfig.config.server.BOT_LLM_MODEL) + "\","
                + "\"stream\":false,"
                + "\"options\":{\"num_predict\":" + YamlConfig.config.server.BOT_LLM_MAX_TOKENS + "},"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + jsonEscape(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + jsonEscape(userMessage) + "\"}"
                + "]}";
    }

    /**
     * Pulls message.content out of an Ollama /api/chat response.
     * Anchors on "message" first so a "content" key elsewhere in the envelope cannot match.
     */
    static Optional<String> extractContent(String json) {
        if (json == null) {
            return Optional.empty();
        }
        int messageAt = json.indexOf("\"message\"");
        int keyAt = json.indexOf("\"content\"", Math.max(messageAt, 0));
        if (keyAt < 0) {
            return Optional.empty();
        }
        int colonAt = json.indexOf(':', keyAt + "\"content\"".length());
        if (colonAt < 0) {
            return Optional.empty();
        }

        int i = colonAt + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return Optional.empty();
        }
        i++;

        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '"') {
                return Optional.of(out.toString());
            }
            if (ch != '\\') {
                out.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= json.length()) {
                return Optional.empty();
            }
            char escaped = json.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 4 >= json.length()) {
                        return Optional.empty();
                    }
                    try {
                        out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                    i += 4;
                }
                default -> out.append(escaped);
            }
            i++;
        }
        return Optional.empty();   // unterminated string
    }

    /**
     * Chat is a single line with a length limit, so collapse whitespace and truncate.
     */
    static String tidyForChat(String reply) {
        String flat = reply.replaceAll("\\s+", " ").trim();
        int max = YamlConfig.config.server.BOT_LLM_MAX_REPLY_CHARS;
        if (flat.length() <= max) {
            return flat;
        }
        // Prefer cutting at a sentence end so a truncated reply still reads as finished.
        String head = flat.substring(0, max);
        int lastStop = Math.max(head.lastIndexOf('.'), Math.max(head.lastIndexOf('!'), head.lastIndexOf('?')));
        if (lastStop > max / 2) {
            return head.substring(0, lastStop + 1);
        }
        // No sentence end to cut at, so append an ellipsis - reserving room for it first,
        // otherwise the result overshoots the cap it exists to enforce.
        final String ellipsis = "...";
        return flat.substring(0, Math.max(0, max - ellipsis.length())).trim() + ellipsis;
    }

    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
