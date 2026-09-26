package com.example.discordbot.discord;

import com.example.discordbot.config.Timing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Outbound calls to Discord (contracts/discord-outbound.md). Every call has explicit connect and
 * request timeouts (Principle III), every message carries an empty {@code allowed_mentions} list
 * so report text cannot ping anyone (research.md R13), and no result ever contains the address,
 * a token, or a response body (Principle IV).
 */
public final class DiscordClient {

    public static final String DEFAULT_BASE = "https://discord.com/api/v10";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String base;
    private final String botToken;
    private final Duration requestTimeout;

    public DiscordClient(String botToken) {
        this(DEFAULT_BASE, botToken, Timing.OUTBOUND_CONNECT, Timing.OUTBOUND_REQUEST);
    }

    /** Test seam: a stub base address and short timeouts. */
    public DiscordClient(String base, String botToken, Duration connectTimeout, Duration requestTimeout) {
        this.base = base;
        this.botToken = botToken;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /** Edit the deferred private reply. The interaction token in the address is the credential. */
    public DiscordResult editOriginal(String applicationId, String interactionToken, String content) {
        String address = base + "/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original";
        return call("PATCH", address, false, messageBody(content)).result();
    }

    /** Post to a channel as the bot. */
    public DiscordResult postMessage(String channelId, String content) {
        return call("POST", base + "/channels/" + channelId + "/messages", true, messageBody(content)).result();
    }

    /** Execute the second-channel webhook. The address itself is the credential. */
    public DiscordResult executeWebhook(String webhookAddress, String content) {
        return call("POST", webhookAddress, false, messageBody(content)).result();
    }

    // ---- dashboard: connecting a server ----------------------------------------------------

    public record Guild(String id, String name) {}

    public record Channel(String id, String name) {}

    /** A lookup result: the classified call outcome plus whatever was read (empty on failure). */
    public record Fetched<T>(DiscordResult result, T value) {}

    /** The servers the bot has been added to. */
    public Fetched<java.util.List<Guild>> listGuilds() {
        Raw raw = call("GET", base + "/users/@me/guilds?limit=200", true, null);
        java.util.List<Guild> guilds = new java.util.ArrayList<>();
        if (raw.result().isSuccess()) {
            for (JsonNode n : parseArray(raw.body())) {
                guilds.add(new Guild(n.path("id").asText(), n.path("name").asText()));
            }
        }
        return new Fetched<>(raw.result(), guilds);
    }

    /** A server's text channels (Discord channel type 0). */
    public Fetched<java.util.List<Channel>> listChannels(String guildId) {
        Raw raw = call("GET", base + "/guilds/" + guildId + "/channels", true, null);
        java.util.List<Channel> channels = new java.util.ArrayList<>();
        if (raw.result().isSuccess()) {
            for (JsonNode n : parseArray(raw.body())) {
                if (n.path("type").asInt(-1) == 0) {
                    channels.add(new Channel(n.path("id").asText(), n.path("name").asText()));
                }
            }
        }
        return new Fetched<>(raw.result(), channels);
    }

    /** Registers (bulk-overwrites) the slash commands for one server. Safe to repeat. */
    public DiscordResult registerCommands(String applicationId, String guildId) {
        String address = base + "/applications/" + applicationId + "/guilds/" + guildId + "/commands";
        return call("PUT", address, true, CommandDefinitions.json()).result();
    }

    private static JsonNode parseArray(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            return node.isArray() ? node : MAPPER.createArrayNode();
        } catch (IOException | RuntimeException e) {
            return MAPPER.createArrayNode();
        }
    }

    // ---- internals ---------------------------------------------------------------------

    record Raw(DiscordResult result, String body) {}

    Raw call(String method, String address, boolean asBot, String jsonBody) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(address))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (discord-bot, 1.0)");
            if (asBot) {
                builder.header("Authorization", "Bot " + botToken);
            }
            request = builder.method(method, jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        } catch (IllegalArgumentException e) {
            // The exception message would echo the address, which can contain a token.
            return new Raw(DiscordResult.permanent(0, 0, "invalid address"), null);
        }
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Raw(classify(response), response.body());
        } catch (HttpTimeoutException e) {
            return new Raw(DiscordResult.retryable(0, 0, 0, "timeout"), null);
        } catch (IOException e) {
            return new Raw(DiscordResult.retryable(0, 0, 0, "network error"), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Raw(DiscordResult.retryable(0, 0, 0, "interrupted"), null);
        }
    }

    private static DiscordResult classify(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return DiscordResult.success(status);
        }
        int code = errorCode(response.body());
        if (status == 429) {
            return DiscordResult.retryable(status, code, retryAfter(response), "HTTP 429");
        }
        if (status >= 500) {
            return DiscordResult.retryable(status, code, 0, "HTTP " + status);
        }
        return DiscordResult.permanent(status, code, code > 0 ? "HTTP " + status + " code " + code : "HTTP " + status);
    }

    private static int errorCode(String body) {
        try {
            return body == null ? 0 : MAPPER.readTree(body).path("code").asInt(0);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static double retryAfter(HttpResponse<String> response) {
        try {
            JsonNode n = MAPPER.readTree(response.body()).path("retry_after");
            if (n.isNumber()) {
                return n.asDouble();
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the header
        }
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }).orElse(1.0);
    }

    static String messageBody(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", truncate(content));
        node.putObject("allowed_mentions").putArray("parse");
        return node.toString();
    }

    static String truncate(String content) {
        if (content == null) {
            return "";
        }
        int max = Timing.MAX_MESSAGE_CHARS;
        return content.length() <= max ? content : content.substring(0, max - 1) + "…";
    }
}
