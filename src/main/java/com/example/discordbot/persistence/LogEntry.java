package com.example.discordbot.persistence;

import java.time.Instant;
import java.util.List;

/**
 * One row of the dashboard log. It deliberately has no field for the interaction token or any
 * address, so nothing sensitive can reach a page or the JSON API (constitution Principle IV).
 * {@code member} and {@code text} are untrusted and must be rendered as plain text.
 */
public record LogEntry(
        String id,
        Instant receivedAt,
        String member,
        String command,
        String text,
        boolean priority,
        String outcome,
        List<LogAction> actions) {

    /** One action of an interaction, as shown in the log. */
    public record LogAction(String kind, String status, int attempts, String lastError) {}
}
