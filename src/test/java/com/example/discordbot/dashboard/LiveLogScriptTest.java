package com.example.discordbot.dashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards FR-021: the live-log script must write untrusted text with textContent and never as
 * markup. A cheap static check, so a later edit cannot quietly introduce an XSS hole.
 */
class LiveLogScriptTest {

    private static final Path SCRIPT = Path.of("src/main/webapp/static/js/live-log.js");

    @Test
    void writesWithTextContent() throws IOException {
        assertTrue(Files.readString(SCRIPT).contains("textContent"));
    }

    @Test
    void neverUsesAnyApiThatParsesMarkup() throws IOException {
        String script = Files.readString(SCRIPT);
        for (String forbidden : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")) {
            assertFalse(script.contains(forbidden), "live-log.js uses " + forbidden);
        }
    }

    @Test
    void pausesWhenHiddenOrIdleSoAnOpenTabCannotKeepTheDatabaseAwake() throws IOException {
        String script = Files.readString(SCRIPT);
        assertTrue(script.contains("document.hidden"));
        assertTrue(script.contains("IDLE_LIMIT_MS"));
        assertTrue(script.contains("10 * 60 * 1000"));
        assertTrue(script.contains("POLL_MS = 3000"));
    }

    @Test
    void sendsTheBrowserToSignInOn401() throws IOException {
        String script = Files.readString(SCRIPT);
        assertTrue(script.contains("401"));
        assertTrue(script.contains("loginUrl"));
    }
}
