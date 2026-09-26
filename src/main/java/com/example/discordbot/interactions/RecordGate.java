package com.example.discordbot.interactions;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.CommitPermit;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Recorder;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Enforces the record deadline without ever accepting a command that was already refused
 * (research.md R2, constitution Principle II).
 *
 * <p>The request thread and the database thread share one three-state flag. The database thread
 * moves it PENDING to COMMITTING just before it commits (the point of no return, through
 * {@link CommitPermit}); the request thread moves it PENDING to ABANDONED at the deadline.
 * Exactly one wins, so a refused command is rolled back and can never be recorded later.
 */
public final class RecordGate {

    public enum Result {
        /** Recorded: acknowledge, then start the actions. */
        ACCEPTED,
        /** Already recorded by an earlier delivery: acknowledge, do nothing else. */
        DUPLICATE,
        /** Could not be recorded in time: reply "try again"; nothing was accepted. */
        REFUSED,
        /** A commit was in flight and its outcome is unknown after the grace period. */
        UNCONFIRMED
    }

    private static final int PENDING = 0;
    private static final int COMMITTING = 1;
    private static final int ABANDONED = 2;

    private final Recorder recorder;
    private final Duration deadline;
    private final Duration grace;
    private final ExecutorService executor;

    public RecordGate(Recorder recorder, Duration deadline, Duration grace, ExecutorService executor) {
        this.recorder = recorder;
        this.deadline = deadline;
        this.grace = grace;
        this.executor = executor;
    }

    /**
     * @param onLateRecorded called exactly once with the interaction id if an UNCONFIRMED commit
     *     later lands as recorded; never called for a commit that fails or is a duplicate
     * @param startNanos {@link System#nanoTime()} taken when the request arrived
     */
    public Result record(Interaction interaction, Planner planner, Consumer<String> onLateRecorded, long startNanos) {
        AtomicInteger state = new AtomicInteger(PENDING);
        CommitPermit permit = () -> state.compareAndSet(PENDING, COMMITTING);
        CompletableFuture<Recorder.Outcome> work = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    work.complete(recorder.record(interaction, planner, permit));
                } catch (Throwable t) {
                    work.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException saturated) {
            return Result.REFUSED;
        }

        long remaining = Math.max(1L, deadline.toNanos() - (System.nanoTime() - startNanos));
        try {
            return map(work.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException deadlinePassed) {
            return afterDeadline(work, state, interaction.id(), onLateRecorded);
        } catch (ExecutionException failed) {
            // A failure after the point of no return may or may not have committed.
            return state.get() == COMMITTING ? Result.UNCONFIRMED : Result.REFUSED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state.compareAndSet(PENDING, ABANDONED);
            return Result.REFUSED;
        }
    }

    private Result afterDeadline(
            CompletableFuture<Recorder.Outcome> work, AtomicInteger state, String id, Consumer<String> onLate) {
        if (state.compareAndSet(PENDING, ABANDONED)) {
            return Result.REFUSED; // the database thread will see a refused permit and roll back
        }
        // The commit is already in flight and cannot be cancelled: wait a short grace period.
        try {
            return map(work.get(grace.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException stillUnknown) {
            work.thenAccept(outcome -> {
                if (outcome == Recorder.Outcome.RECORDED) {
                    onLate.accept(id);
                }
            });
            return Result.UNCONFIRMED;
        } catch (ExecutionException failed) {
            return Result.UNCONFIRMED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.UNCONFIRMED;
        }
    }

    private static Result map(Recorder.Outcome outcome) {
        return switch (outcome) {
            case RECORDED -> Result.ACCEPTED;
            case DUPLICATE -> Result.DUPLICATE;
            case ABANDONED -> Result.REFUSED;
        };
    }
}
