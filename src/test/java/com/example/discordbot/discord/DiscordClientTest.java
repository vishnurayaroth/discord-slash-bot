package com.example.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Discord is replaced by a JDK HttpServer stub; nothing here touches the network. */
class DiscordClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_TOKEN = "SECRET-INTERACTION-TOKEN";

    private HttpServer server;
    private String base;
    private volatile int status = 200;
    private volatile String responseBody = "";
    private volatile String responseHeader;
    private volatile long delayMillis = 0;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responseHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", responseHeader);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DiscordClient client() {
        return new DiscordClient(base, "BOT-SECRET", Duration.ofSeconds(1), Duration.ofMillis(300));
    }

    @Test
    void editOriginalUsesTheTokenInTheAddressAndNoAuthorizationHeader() throws Exception {
        DiscordResult r = client().editOriginal("app1", SECRET_TOKEN, "hello");
        assertTrue(r.isSuccess());
        assertEquals("PATCH", method.get());
        assertEquals("/webhooks/app1/" + SECRET_TOKEN + "/messages/@original", path.get());
        assertNull(authorization.get());
        JsonNode body = MAPPER.readTree(requestBody.get());
        assertEquals("hello", body.get("content").asText());
        assertEquals(0, body.get("allowed_mentions").get("parse").size(), "no mentions may be parsed");
    }

    @Test
    void postMessageAuthorizesAsTheBot() throws Exception {
        assertTrue(client().postMessage("chan9", "hi").isSuccess());
        assertEquals("POST", method.get());
        assertEquals("/channels/chan9/messages", path.get());
        assertEquals("Bot BOT-SECRET", authorization.get());
    }

    @Test
    void webhookNoContentIsSuccess() {
        status = 204;
        assertTrue(client().executeWebhook(base + "/hook/1/abc", "note").isSuccess());
        assertEquals("/hook/1/abc", path.get());
        assertNull(authorization.get());
    }

    @Test
    void rateLimitIsRetryableAndHonorsRetryAfterFromTheBody() {
        status = 429;
        responseBody = "{\"message\":\"slow down\",\"retry_after\":1.5,\"global\":false}";
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.RETRYABLE, r.kind());
        assertEquals(1.5, r.retryAfterSeconds());
    }

    @Test
    void rateLimitFallsBackToTheRetryAfterHeader() {
        status = 429;
        responseBody = "not json";
        responseHeader = "2";
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.RETRYABLE, r.kind());
        assertEquals(2.0, r.retryAfterSeconds());
    }

    @Test
    void serverErrorsAreRetryable() {
        status = 503;
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.RETRYABLE, r.kind());
        assertEquals(503, r.status());
    }

    @Test
    void otherClientErrorsArePermanentAndCarryTheDiscordCode() {
        status = 403;
        responseBody = "{\"code\":50001,\"message\":\"Missing Access\"}";
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.PERMANENT, r.kind());
        assertEquals(403, r.status());
        assertEquals(50001, r.code());
        assertEquals("HTTP 403 code 50001", r.error());

        status = 404;
        responseBody = "";
        DiscordResult notFound = client().editOriginal("a", "t", "x");
        assertEquals(DiscordResult.Kind.PERMANENT, notFound.kind());
        assertEquals(404, notFound.status());
    }

    @Test
    void aSlowServerIsCutOffByTheRequestTimeout() {
        delayMillis = 1200;
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.RETRYABLE, r.kind());
        assertEquals("timeout", r.error());
    }

    @Test
    void anUnreachableServerIsRetryable() {
        server.stop(0);
        DiscordResult r = client().postMessage("c", "x");
        assertEquals(DiscordResult.Kind.RETRYABLE, r.kind());
        assertEquals("network error", r.error());
    }

    @Test
    void anInvalidAddressIsPermanentAndDoesNotEchoTheAddress() {
        DiscordResult r = client().executeWebhook("not a url with SECRET-WEBHOOK-TOKEN", "x");
        assertEquals(DiscordResult.Kind.PERMANENT, r.kind());
        assertFalse(r.error().contains("SECRET-WEBHOOK-TOKEN"));
    }

    @Test
    void resultsNeverContainTheAddressTokenOrBody() {
        status = 500;
        responseBody = "{\"echo\":\"" + SECRET_TOKEN + "\"}";
        DiscordResult r = client().editOriginal("app1", SECRET_TOKEN, "x");
        String all = r.toString();
        assertFalse(all.contains(SECRET_TOKEN), "result leaked the interaction token");
        assertFalse(all.contains("127.0.0.1"), "result leaked the address");
    }

    @Test
    void longContentIsTruncatedToDiscordsLimit() throws Exception {
        client().postMessage("c", "a".repeat(3000));
        String content = MAPPER.readTree(requestBody.get()).get("content").asText();
        assertEquals(2000, content.length());
    }
}
