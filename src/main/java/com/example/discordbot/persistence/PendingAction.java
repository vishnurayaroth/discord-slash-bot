package com.example.discordbot.persistence;

import java.time.Instant;

/**
 * An action joined with what is needed to run it. {@code interactionToken} is sensitive: it is
 * never logged or returned by any API (constitution Principle IV).
 */
public record PendingAction(
        long id,
        String interactionId,
        String kind,
        String payload,
        int attempts,
        String status,
        String interactionToken,
        Instant tokenExpiresAt,
        Instant receivedAt,
        Instant nextAttemptAt) {}
