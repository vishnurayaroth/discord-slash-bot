package com.example.discordbot.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Discord and the mirror webhook, built on the JDK's HttpServer. Routes are
 * matched by longest path prefix and can be changed while a test runs; every request is recorded.
 */
public final class StubServer implements AutoCloseable {

    public record Hit(String method, String path, String authorization, String body, long atNanos) {}

    public static final class Route {
        public volatile int status = 200;
        public volatile String body = "";
        public volatile long delayMillis = 0;
    }

    private final HttpServer server;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<Hit> hits = new CopyOnWriteArrayList<>();

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(new Hit(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), System.nanoTime()));
            Route route = match(path);
            if (route.delayMillis > 0) {
                try {
                    Thread.sleep(route.delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = route.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(route.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The (mutable) route for a path prefix; created on first use. */
    public Route route(String pathPrefix) {
        return routes.computeIfAbsent(pathPrefix, p -> new Route());
    }

    public Route route(String pathPrefix, int status, String body, long delayMillis) {
        Route r = route(pathPrefix);
        r.status = status;
        r.body = body;
        r.delayMillis = delayMillis;
        return r;
    }

    public List<Hit> hits(String pathPrefix) {
        return hits.stream().filter(h -> h.path().startsWith(pathPrefix)).toList();
    }

    public List<Hit> allHits() {
        return List.copyOf(hits);
    }

    private Route match(String path) {
        String best = null;
        for (String prefix : routes.keySet()) {
            if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? new Route() : routes.get(best);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
