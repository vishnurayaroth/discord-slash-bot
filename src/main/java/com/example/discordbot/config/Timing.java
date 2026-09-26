package com.example.discordbot.config;

import java.time.Duration;

/**
 * Every timing decision in one place (plan.md "Decisions the spec left to planning",
 * research.md R2, R5, R6, R7). Nothing else in the code base hard-codes a duration.
 */
public final class Timing {

    private Timing() {}

    /** Replay window: a request may be at most this old (R6). */
    public static final Duration FRESH_PAST = Duration.ofSeconds(15);
    /** Replay window: a request may be at most this far in the future (clock drift, R6). */
    public static final Duration FRESH_FUTURE = Duration.ofSeconds(5);
    /** Largest request body the endpoint reads. */
    public static final int MAX_BODY_BYTES = 100 * 1024;

    /** Every outbound HTTP call: connect and whole-request limits (R7). */
    public static final Duration OUTBOUND_CONNECT = Duration.ofSeconds(3);
    public static final Duration OUTBOUND_REQUEST = Duration.ofSeconds(5);

    /**
     * Whole record step, measured from when the request arrives (R2).
     * PROVISIONAL at 2.5 s until the cold-database measurement (R1, task T013) is recorded.
     */
    public static final Duration RECORD_DEADLINE = Duration.ofMillis(2500);
    /** Extra wait when a commit is already in flight at the deadline (R2). */
    public static final Duration COMMIT_GRACE = Duration.ofMillis(400);
    /** Pool wait for a connection (HikariCP connectionTimeout, R8). */
    public static final int DB_CONNECTION_WAIT_MS = 1500;
    /** Limit applied to every statement by Database.prepare (Principle III). */
    public static final int DB_STATEMENT_LIMIT_SECONDS = 1;
    /** Schema creation may legitimately take longer than a request-path statement. */
    public static final int DB_SCHEMA_LIMIT_SECONDS = 20;

    /** Discord's interaction token lifetime and the margin kept before it expires (R5). */
    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    public static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);
    /** The first reply attempt waits this long after the acknowledgement is sent (R5). */
    public static final Duration FIRST_REPLY_DELAY = Duration.ofMillis(500);
    /** A 404 on the first attempts of a reply within this window is retryable (R5). */
    public static final Duration EARLY_404_WINDOW = Duration.ofSeconds(10);
    public static final int EARLY_404_ATTEMPTS = 3;

    /** Retry schedule for the post and mirror actions (R5). */
    public static final Duration MIRROR_BASE = Duration.ofSeconds(10);
    public static final Duration MIRROR_CAP = Duration.ofSeconds(300);
    public static final int MIRROR_MAX_ATTEMPTS = 20;
    /** Retry schedule for the reply action (R5). */
    public static final Duration REPLY_BASE = Duration.ofSeconds(2);
    public static final Duration REPLY_CAP = Duration.ofSeconds(120);
    /** Random spread applied to every retry delay: plus or minus this fraction. */
    public static final double JITTER = 0.20;

    /** Discord's message length limit. */
    public static final int MAX_MESSAGE_CHARS = 2000;
    /** Longest report text a member can enter (max_length of the option). */
    public static final int REPORT_TEXT_MAX = 1000;
}
