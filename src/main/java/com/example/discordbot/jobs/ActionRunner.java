package com.example.discordbot.jobs;

import com.example.discordbot.config.AppConfig;
import com.example.discordbot.discord.DiscordClient;
import com.example.discordbot.discord.DiscordResult;
import com.example.discordbot.persistence.ActionStore;
import com.example.discordbot.persistence.InteractionStore;
import com.example.discordbot.persistence.PendingAction;
import com.example.discordbot.persistence.ServerConnection;
import com.example.discordbot.persistence.ServerConnectionStore;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Runs one attempt of one action (reply, post or mirror) and records the result. Actions are
 * independent: a failing mirror never blocks or delays the reply (FR-017). Only ids, kinds and
 * status codes are logged (constitution Principle IV).
 */
public final class ActionRunner {

    private static final System.Logger LOG = System.getLogger(ActionRunner.class.getName());

    /** What to do after a failed attempt. {@code reason} becomes the stored error when failing. */
    public record Decision(boolean retry, Duration delay, String reason) {
        public static Decision retryAfter(Duration delay) {
            return new Decision(true, delay, null);
        }

        public static Decision fail(String reason) {
            return new Decision(false, Duration.ZERO, reason);
        }
    }

    /** Decides retry versus permanent failure. */
    @FunctionalInterface
    public interface FailureHandler {
        Decision decide(PendingAction action, int attemptsMade, DiscordResult result, Instant now);
    }

    /** Schedules a later attempt. */
    @FunctionalInterface
    public interface Retrier {
        void schedule(long actionId, Duration delay);
    }

    private final ActionStore actions;
    private final InteractionStore interactions;
    private final ServerConnectionStore connections;
    private final DiscordClient client;
    private final AppConfig config;
    private final FailureHandler failureHandler;
    private final Clock clock;
    private volatile Retrier retrier = (id, delay) -> { };

    public ActionRunner(ActionStore actions, InteractionStore interactions, ServerConnectionStore connections,
            DiscordClient client, AppConfig config, FailureHandler failureHandler, Clock clock) {
        this.actions = actions;
        this.interactions = interactions;
        this.connections = connections;
        this.client = client;
        this.config = config;
        this.failureHandler = failureHandler;
        this.clock = clock;
    }

    public void setRetrier(Retrier retrier) {
        this.retrier = retrier;
    }

    /** Failure handler used until retries are wired: permanent errors fail, others stay pending. */
    public static FailureHandler simpleHandler() {
        return (action, attempts, result, now) -> result.kind() == DiscordResult.Kind.PERMANENT
                ? Decision.fail(result.error())
                : Decision.retryAfter(Duration.ZERO);
    }

    /** One attempt. Does nothing for an action that is no longer pending. */
    public void run(long actionId) {
        PendingAction action;
        try {
            Optional<PendingAction> found = actions.load(actionId);
            if (found.isEmpty() || !"pending".equals(found.get().status())) {
                return;
            }
            action = found.get();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "could not load action {0} ({1})", actionId, e.getClass().getSimpleName());
            return;
        }

        int attempts = action.attempts() + 1;
        DiscordResult result = execute(action);
        try {
            if (result.isSuccess()) {
                actions.markSucceeded(action.id(), attempts);
                finishReply(action);
                return;
            }
            Decision decision = failureHandler.decide(action, attempts, result, clock.instant());
            if (decision.retry()) {
                actions.markRetry(action.id(), attempts, clock.instant().plus(decision.delay()), result.error());
                retrier.schedule(action.id(), decision.delay());
            } else {
                actions.markFailed(action.id(), attempts, decision.reason());
                finishReply(action);
                LOG.log(System.Logger.Level.WARNING, "action {0} ({1}) failed permanently: {2}",
                        action.id(), action.kind(), decision.reason());
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "could not record the result of action {0} ({1})",
                    action.id(), e.getClass().getSimpleName());
        }
    }

    private void finishReply(PendingAction action) throws SQLException {
        if ("reply".equals(action.kind())) {
            interactions.clearToken(action.interactionId()); // the credential is no longer needed
        }
    }

    private DiscordResult execute(PendingAction action) {
        switch (action.kind()) {
            case "reply":
                if (action.interactionToken() == null) {
                    return DiscordResult.permanent(0, 0, "interaction token unavailable");
                }
                return client.editOriginal(config.applicationId(), action.interactionToken(), action.payload());
            case "post":
                try {
                    Optional<ServerConnection> connection = connections.get();
                    if (connection.isEmpty()) {
                        return DiscordResult.permanent(0, 0, "no channel connected");
                    }
                    return client.postMessage(connection.get().channelId(), action.payload());
                } catch (SQLException e) {
                    return DiscordResult.retryable(0, 0, 0, "database unavailable");
                }
            case "mirror":
                return client.executeWebhook(config.mirrorWebhookUrl(), action.payload());
            default:
                return DiscordResult.permanent(0, 0, "unknown action kind");
        }
    }
}
