package com.example.discordbot.config;

import jakarta.servlet.ServletContext;

/**
 * Tiny service locator over the ServletContext: {@link AppLifecycle} registers each service once
 * at start-up, and servlets look them up by type. Keeps the servlets thin.
 */
public final class Services {

    private Services() {}

    public static <T> void put(ServletContext context, Class<T> type, T service) {
        context.setAttribute(type.getName(), service);
    }

    /** Null when the service was not registered (for example, start-up failed). */
    public static <T> T get(ServletContext context, Class<T> type) {
        return type.cast(context.getAttribute(type.getName()));
    }
}
