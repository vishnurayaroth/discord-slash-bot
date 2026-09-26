package com.example.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    private static Map<String, String> full() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_APPLICATION_ID", "app-id-value");
        env.put("DISCORD_PUBLIC_KEY", "public-key-value");
        env.put("DISCORD_BOT_TOKEN", "bot-token-secret-value");
        env.put("MIRROR_WEBHOOK_URL", "https://example.test/hook-secret-value");
        env.put("DATABASE_URL", "jdbc:postgresql://db-host-value/db");
        env.put("DB_USER", "db-user-value");
        env.put("DB_PASSWORD", "db-password-secret-value");
        env.put("ADMIN_USERNAME", "admin-name-value");
        env.put("ADMIN_PASSWORD_HASH", "hash-secret-value");
        return env;
    }

    @Test
    void loadsWhenEverythingIsPresent() {
        AppConfig config = AppConfig.from(full()::get);
        assertEquals("app-id-value", config.applicationId());
        assertEquals("bot-token-secret-value", config.botToken());
    }

    @Test
    void missingVariableFailsNamingItButNeverAnyValue() {
        Map<String, String> env = full();
        env.remove("DISCORD_BOT_TOKEN");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppConfig.from(env::get));
        assertTrue(e.getMessage().contains("DISCORD_BOT_TOKEN"));
        for (String value : env.values()) {
            assertFalse(e.getMessage().contains(value), "message leaked a value");
        }
    }

    @Test
    void blankCountsAsMissingAndAllMissingOnesAreListed() {
        Map<String, String> env = full();
        env.put("DB_USER", "   ");
        env.remove("DB_PASSWORD");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppConfig.from(env::get));
        assertTrue(e.getMessage().contains("DB_USER"));
        assertTrue(e.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    void toStringPrintsNoValues() {
        String text = AppConfig.from(full()::get).toString();
        for (String value : full().values()) {
            assertFalse(text.contains(value), "toString leaked a value");
        }
    }
}
