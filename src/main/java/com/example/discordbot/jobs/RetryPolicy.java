package com.example.discordbot.jobs;

import com.example.discordbot.config.Timing;
import com.example.discordbot.discord.DiscordResult;
import com.example.discordbot.persistence.PendingAction;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * The retry rules of research.md R5 as a pure decision: no clock, no I/O, no shared state.
 *
 * <ul>
 *   <li>Permanent failures (4xx other than 429) fail at once, except a 404 on the first reply
 *       attempts, which is expected when the edit races Discord's acknowledgement.
 *   <li>Delays double from the base up to the cap, then get plus or minus 20% jitter; a 429's
 *       {@code retry_after} wins when it is longer.
 *   <li>Post and mirror stop after a fixed number of attempts; a reply stops 30 s before its
 *       interaction token expires.
 * </ul>
 *
 * The reason on a failure is built only from status codes and fixed phrases (Principle IV).
 */
public final class RetryPolicy implements ActionRunner.FailureHandler {

    private final DoubleSupplier jitter; // a value in [-1, 1]
    private final Duration mirrorBase;
    private final Duration mirrorCap;
    private final Duration replyBase;
    private final Duration replyCap;
    private final int maxAttempts;

    public RetryPolicy() {
        this(() -> ThreadLocalRandom.current().nextDouble(-1.0, 1.0), Timing.MIRROR_BASE, Timing.MIRROR_CAP,
                Timing.REPLY_BASE, Timing.REPLY_CAP, Timing.MIRROR_MAX_ATTEMPTS);
    }

    /** Test seam: deterministic jitter and short delays. */
    public RetryPolicy(DoubleSupplier jitter, Duration mirrorBase, Duration mirrorCap, Duration replyBase,
            Duration replyCap, int maxAttempts) {
        this.jitter = jitter;
        this.mirrorBase = mirrorBase;
        this.mirrorCap = mirrorCap;
        this.replyBase = replyBase;
        this.replyCap = replyCap;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ActionRunner.Decision decide(PendingAction action, int attemptsMade, DiscordResult result, Instant now) {
        boolean reply = "reply".equals(action.kind());

        if (result.kind() == DiscordResult.Kind.PERMANENT && !(reply && isEarly404(action, attemptsMade, result, now))) {
            return ActionRunner.Decision.fail(result.error());
        }

        Duration delay = backoff(reply ? replyBase : mirrorBase, reply ? replyCap : mirrorCap, attemptsMade);
        if (result.retryAfterSeconds() > 0) {
            Duration asked = Duration.ofMillis((long) (result.retryAfterSeconds() * 1000));
            if (asked.compareTo(delay) > 0) {
                delay = asked;
            }
        }

        if (reply) {
            Instant lastUsefulMoment = action.tokenExpiresAt() == null
                    ? null
                    : action.tokenExpiresAt().minus(Timing.TOKEN_MARGIN);
            if (lastUsefulMoment == null || now.plus(delay).isAfter(lastUsefulMoment)) {
                return ActionRunner.Decision.fail("follow-up window expired");
            }
        } else if (attemptsMade >= maxAttempts) {
            return ActionRunner.Decision.fail("retry limit reached (last error: " + result.error() + ")");
        }
        return ActionRunner.Decision.retryAfter(delay);
    }

    /** {@code min(base * 2^(n-1), cap)} with plus or minus 20% jitter. */
    Duration backoff(Duration base, Duration cap, int attemptsMade) {
        double seconds = base.toMillis() / 1000.0;
        for (int i = 1; i < attemptsMade && seconds < cap.toMillis() / 1000.0; i++) {
            seconds *= 2;
        }
        seconds = Math.min(seconds, cap.toMillis() / 1000.0);
        seconds *= 1.0 + Timing.JITTER * jitter.getAsDouble();
        return Duration.ofMillis(Math.max(0, Math.round(seconds * 1000)));
    }

    private static boolean isEarly404(PendingAction action, int attemptsMade, DiscordResult result, Instant now) {
        return result.status() == 404
                && attemptsMade <= Timing.EARLY_404_ATTEMPTS
                && action.receivedAt() != null
                && Duration.between(action.receivedAt(), now).compareTo(Timing.EARLY_404_WINDOW) <= 0;
    }
}
