package com.example.discordbot.jobs;

import com.example.discordbot.persistence.ActionStore;
import com.example.discordbot.persistence.PendingAction;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Retry timers held in memory, plus one recovery scan at start-up. There is deliberately no
 * periodic database polling: an idle scheduler makes no queries, so it cannot keep the free
 * Neon database awake (research.md R4, R5).
 */
public final class RetryScheduler implements ActionRunner.Retrier {

    private static final System.Logger LOG = System.getLogger(RetryScheduler.class.getName());

    private final ActionStore actions;
    private final ActionRunner runner;
    private final ScheduledExecutorService executor;
    private final Clock clock;

    public RetryScheduler(ActionStore actions, ActionRunner runner, ScheduledExecutorService executor, Clock clock) {
        this.actions = actions;
        this.runner = runner;
        this.executor = executor;
        this.clock = clock;
    }

    /** Schedules the next attempt of one action. */
    @Override
    public void schedule(long actionId, Duration delay) {
        executor.schedule(() -> runner.run(actionId), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    /**
     * One query: reschedules every pending action from its stored next-attempt time, so work that
     * was accepted before a restart is finished afterwards (FR-016).
     *
     * @return how many actions were rescheduled
     */
    public int recover() throws SQLException {
        List<PendingAction> pending = actions.pending();
        for (PendingAction action : pending) {
            Duration wait = Duration.between(clock.instant(), action.nextAttemptAt());
            schedule(action.id(), wait.isNegative() ? Duration.ZERO : wait);
        }
        return pending.size();
    }

    /**
     * Waits (without touching the database) until the schema is ready, then runs the recovery scan
     * once, retrying a few times if the database is still waking up.
     */
    public void recoverWhenReady(BooleanSupplier schemaReady) {
        Thread t = new Thread(() -> {
            try {
                while (!schemaReady.getAsBoolean()) {
                    Thread.sleep(500);
                }
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        int count = recover();
                        LOG.log(System.Logger.Level.INFO, "recovery scan rescheduled {0} pending action(s)", count);
                        return;
                    } catch (SQLException e) {
                        LOG.log(System.Logger.Level.WARNING, "recovery scan failed ({0}), attempt {1}",
                                e.getClass().getSimpleName(), attempt);
                        Thread.sleep(2000L * attempt);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "recovery-scan");
        t.setDaemon(true);
        t.start();
    }
}
