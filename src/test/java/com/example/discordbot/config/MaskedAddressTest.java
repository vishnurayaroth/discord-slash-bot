package com.example.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-025: the dashboard may show a masked address, never the full one. */
class MaskedAddressTest {

    private static final String ADDRESS = "https://discord.com/api/webhooks/123456789012345678/SECRETTOKENvalue-abcd";

    @Test
    void keepsTheHostAndTheLastFourCharactersOnly() {
        String masked = AppConfig.mask(ADDRESS);
        assertEquals("https://discord.com/…abcd", masked);
    }

    @Test
    void neverRevealsTheWebhookIdOrTheTokenBody() {
        String masked = AppConfig.mask(ADDRESS);
        assertFalse(masked.contains("123456789012345678"));
        assertFalse(masked.contains("SECRETTOKEN"));
        assertFalse(masked.contains("/api/webhooks"));
        assertTrue(masked.length() < ADDRESS.length() / 2);
    }

    @Test
    void anAbsentAddressSaysNotConfigured() {
        assertEquals("not configured", AppConfig.mask(null));
        assertEquals("not configured", AppConfig.mask("   "));
    }

    @Test
    void aShortOrMalformedValueRevealsNothing() {
        assertEquals("configured", AppConfig.mask("abc"));
        assertEquals("configured", AppConfig.mask("not-an-address-at-all-but-long-enough"));
    }

    @Test
    void theConfigObjectExposesOnlyTheMaskedForm() {
        Map<String, String> env = new HashMap<>();
        for (String name : AppConfig.REQUIRED) {
            env.put(name, "value-of-" + name);
        }
        env.put("MIRROR_WEBHOOK_URL", ADDRESS);
        env.put("DATABASE_URL", "jdbc:postgresql://test-host/test-db");
        AppConfig config = AppConfig.from(env::get);
        assertEquals("https://discord.com/…abcd", config.maskedMirrorAddress());
        assertFalse(config.toString().contains("SECRETTOKEN"));
    }
}
