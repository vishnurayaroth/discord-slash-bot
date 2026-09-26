package com.example.discordbot.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads every environment variable once (the only place they are read) and fails fast when a
 * required one is missing. The failure message names variables, never values, and toString
 * prints no values (constitution Principle IV).
 */
public final class AppConfig {

    /** Required variables, in the order of .env.example. */
    static final List<String> REQUIRED = List.of(
            "DISCORD_APPLICATION_ID",
            "DISCORD_PUBLIC_KEY",
            "DISCORD_BOT_TOKEN",
            "MIRROR_WEBHOOK_URL",
            "DATABASE_URL",
            "DB_USER",
            "DB_PASSWORD",
            "ADMIN_USERNAME",
            "ADMIN_PASSWORD_HASH");

    private final Map<String, String> values;

    private AppConfig(Map<String, String> values) {
        this.values = values;
    }

    public static AppConfig fromEnv() {
        return from(System::getenv);
    }

    /** Test seam: read from any lookup function. */
    public static AppConfig from(Function<String, String> env) {
        Map<String, String> loaded = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) {
            String value = env.apply(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            } else {
                loaded.put(name, value.trim());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variables: " + String.join(", ", missing));
        }
        return new AppConfig(loaded);
    }

    public String applicationId() { return values.get("DISCORD_APPLICATION_ID"); }

    public String publicKeyHex() { return values.get("DISCORD_PUBLIC_KEY"); }

    public String botToken() { return values.get("DISCORD_BOT_TOKEN"); }

    public String mirrorWebhookUrl() { return values.get("MIRROR_WEBHOOK_URL"); }

    public String databaseUrl() { return values.get("DATABASE_URL"); }

    public String dbUser() { return values.get("DB_USER"); }

    public String dbPassword() { return values.get("DB_PASSWORD"); }

    public String adminUsername() { return values.get("ADMIN_USERNAME"); }

    public String adminPasswordHash() { return values.get("ADMIN_PASSWORD_HASH"); }

    @Override
    public String toString() {
        return "AppConfig[" + values.size() + " values loaded]";
    }
}
