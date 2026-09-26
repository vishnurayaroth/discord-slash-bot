package com.example.discordbot.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.DiscordResult;
import com.example.discordbot.persistence.PendingAction;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final RetryPolicy plain = new RetryPolicy(() -> 0.0, Duration.ofSeconds(10), Duration.ofSeconds(300),
            Duration.ofSeconds(2), Duration.ofSeconds(120), 20);

    private static PendingAction action(String kind, int attempts, Instant received, Instant tokenExpires) {
        return new PendingAction(1, "i", kind, "x", attempts, "pending", "tok", tokenExpires, received, received);
    }

    private static PendingAction mirror() {
        return action("mirror", 0, NOW.minusSeconds(60), NOW.plusSeconds(800));
    }

    private static PendingAction reply(Instant received) {
        return action("reply", 0, received, received.plusSeconds(900));
    }

    private static DiscordResult unavailable() {
        return DiscordResult.retryable(503, 0, 0, "HTTP 503");
    }

    @Test
    void mirrorAndPostDelaysDoubleFromTenSecondsToACapOfFiveMinutes() {
        long[] expected = {10, 20, 40, 80, 160, 300, 300, 300};
        for (int attempt = 1; attempt <= expected.length; attempt++) {
            ActionRunner.Decision d = plain.decide(mirror(), attempt, unavailable(), NOW);
            assertTrue(d.retry(), "attempt " + attempt);
            assertEquals(Duration.ofSeconds(expected[attempt - 1]), d.delay(), "attempt " + attempt);
        }
    }

    @Test
    void replyDelaysDoubleFromTwoSecondsToACapOfTwoMinutes() {
        PendingAction reply = reply(NOW);
        long[] expected = {2, 4, 8, 16, 32, 64, 120, 120};
        for (int attempt = 1; attempt <= expected.length; attempt++) {
            ActionRunner.Decision d = plain.decide(reply, attempt, unavailable(), NOW);
            assertEquals(Duration.ofSeconds(expected[attempt - 1]), d.delay(), "attempt " + attempt);
        }
    }

    @Test
    void jitterStaysWithinPlusOrMinusTwentyPercent() {
        RetryPolicy low = new RetryPolicy(() -> -1.0, Duration.ofSeconds(10), Duration.ofSeconds(300),
                Duration.ofSeconds(2), Duration.ofSeconds(120), 20);
        RetryPolicy high = new RetryPolicy(() -> 1.0, Duration.ofSeconds(10), Duration.ofSeconds(300),
                Duration.ofSeconds(2), Duration.ofSeconds(120), 20);
        assertEquals(Duration.ofSeconds(8), low.decide(mirror(), 1, unavailable(), NOW).delay());
        assertEquals(Duration.ofSeconds(12), high.decide(mirror(), 1, unavailable(), NOW).delay());
        assertEquals(Duration.ofSeconds(360), high.decide(mirror(), 9, unavailable(), NOW).delay(),
                "even at the cap the worst delay is 360 s, inside the 10-minute recovery target");
    }

    @Test
    void mirrorStopsAfterTwentyAttemptsWithAFixedReason() {
        assertTrue(plain.decide(mirror(), 19, unavailable(), NOW).retry());
        ActionRunner.Decision last = plain.decide(mirror(), 20, unavailable(), NOW);
        assertFalse(last.retry());
        assertEquals("retry limit reached (last error: HTTP 503)", last.reason());
    }

    @Test
    void aLongerRetryAfterWinsAndAShorterOneDoesNot() {
        DiscordResult slowDown = DiscordResult.retryable(429, 0, 60, "HTTP 429");
        assertEquals(Duration.ofSeconds(60), plain.decide(mirror(), 1, slowDown, NOW).delay());
        DiscordResult brief = DiscordResult.retryable(429, 0, 2, "HTTP 429");
        assertEquals(Duration.ofSeconds(10), plain.decide(mirror(), 1, brief, NOW).delay());
    }

    @Test
    void permanentClientErrorsFailAtOnceWithTheStatusOnly() {
        DiscordResult gone = DiscordResult.permanent(404, 10015, "HTTP 404 code 10015");
        ActionRunner.Decision d = plain.decide(mirror(), 1, gone, NOW);
        assertFalse(d.retry());
        assertEquals("HTTP 404 code 10015", d.reason());
        assertFalse(plain.decide(mirror(), 1, DiscordResult.permanent(403, 50001, "HTTP 403 code 50001"), NOW).retry());
    }

    @Test
    void aReplyFourOhFourInTheFirstThreeAttemptsWithinTenSecondsIsRetried() {
        DiscordResult early = DiscordResult.permanent(404, 10015, "HTTP 404 code 10015");
        PendingAction fresh = reply(NOW.minusSeconds(3));
        assertTrue(plain.decide(fresh, 1, early, NOW).retry());
        assertTrue(plain.decide(fresh, 3, early, NOW).retry());
        assertFalse(plain.decide(fresh, 4, early, NOW).retry(), "the fourth attempt is no longer early");
        assertFalse(plain.decide(reply(NOW.minusSeconds(11)), 1, early, NOW).retry(), "more than 10 s after the acknowledgement");
        assertFalse(plain.decide(mirror(), 1, early, NOW).retry(), "the early rule is for replies only");
    }

    @Test
    void aReplyStopsThirtySecondsBeforeItsTokenExpires() {
        Instant received = NOW;
        PendingAction reply = action("reply", 0, received, NOW.plusSeconds(900));
        // Just inside the window: now + 2 s is well before expiry - 30 s.
        assertTrue(plain.decide(reply, 1, unavailable(), NOW.plusSeconds(800)).retry());
        // Now + 2 s would land after expiry - 30 s (870 s).
        ActionRunner.Decision late = plain.decide(reply, 1, unavailable(), NOW.plusSeconds(869));
        assertFalse(late.retry());
        assertEquals("follow-up window expired", late.reason());
    }

    @Test
    void aReplyWithoutAnExpiryFailsInsteadOfRetryingForever() {
        PendingAction noExpiry = action("reply", 0, NOW, null);
        assertEquals("follow-up window expired", plain.decide(noExpiry, 1, unavailable(), NOW).reason());
    }

    @Test
    void reasonsNeverContainAddressesOrTokens() {
        DiscordResult r = DiscordResult.retryable(0, 0, 0, "network error");
        String reason = plain.decide(mirror(), 20, r, NOW).reason();
        assertFalse(reason.contains("http"));
        assertFalse(reason.contains("tok"));
    }
}
