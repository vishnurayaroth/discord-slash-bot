package com.example.discordbot.jobs;

import com.example.discordbot.interactions.ActionStarter;
import com.example.discordbot.persistence.ActionStore;
import com.example.discordbot.persistence.PendingAction;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts an interaction's actions by id: the reply after a short delay (Discord may not have
 * processed the acknowledgement yet, research.md R5), the post and mirror at once, each as its own
 * task so they never wait for one another. Called only after the acknowledgement is written.
 */
public final class ActionDispatcher implements ActionStarter {

    private static final System.Logger LOG = System.getLogger(ActionDispatcher.class.getName());

    private final ActionStore actions;
    private final ActionRunner runner;
    private final ScheduledExecutorService executor;
    private final Duration firstReplyDelay;

    public ActionDispatcher(ActionStore actions, ActionRunner runner, ScheduledExecutorService executor,
            Duration firstReplyDelay) {
        this.actions = actions;
        this.runner = runner;
        this.executor = executor;
        this.firstReplyDelay = firstReplyDelay;
    }

    @Override
    public void start(String interactionId) {
        // Loading is a database call, so it also runs off the request path.
        executor.execute(() -> {
            try {
                for (PendingAction action : actions.loadByInteraction(interactionId)) {
                    if ("pending".equals(action.status())) {
                        schedule(action.id(), "reply".equals(action.kind()) ? firstReplyDelay : Duration.ZERO);
                    }
                }
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.WARNING, "could not load the actions of {0} ({1})",
                        interactionId, e.getClass().getSimpleName());
            }
        });
    }

    public void schedule(long actionId, Duration delay) {
        executor.schedule(() -> runner.run(actionId), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }
}
