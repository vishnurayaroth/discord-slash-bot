package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The start-up recovery query (task T056): only pending actions, with what is needed to resume them. */
class ActionRecoveryTest {

    private Database db;
    private InteractionStore interactions;
    private ActionStore actions;

    @BeforeEach
    void fresh() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        interactions = new InteractionStore(db);
        actions = new ActionStore(db);
    }

    private void record(String id) throws SQLException {
        interactions.record(new Interaction(id, 2, "tok-" + id, "app", "g1", "c1", "u1", "alice", "report", "x"),
                (i, s) -> new Plan("handled", false, List.of(new NewAction("reply", "r"), new NewAction("mirror", "m"))),
                () -> true);
    }

    @Test
    void returnsOnlyPendingActionsWithTheirTokenExpiry() throws SQLException {
        record("a");
        record("b");
        long done = actions.loadByInteraction("a").get(0).id();
        long failed = actions.loadByInteraction("b").get(1).id();
        actions.markSucceeded(done, 1);
        actions.markFailed(failed, 2, "HTTP 404");

        List<PendingAction> pending = actions.pending();
        assertEquals(2, pending.size(), "one action of each interaction is still pending");
        for (PendingAction p : pending) {
            assertEquals("pending", p.status());
            assertNotNull(p.tokenExpiresAt(), "the reply must know when its token expires");
            assertTrue(p.tokenExpiresAt().isAfter(Instant.now()));
        }
    }

    @Test
    void isOrderedByTheNextAttemptTime() throws SQLException {
        record("a");
        List<PendingAction> both = actions.loadByInteraction("a");
        actions.markRetry(both.get(0).id(), 1, Instant.now().plusSeconds(600), "HTTP 503");
        actions.markRetry(both.get(1).id(), 1, Instant.now().plusSeconds(5), "HTTP 503");
        List<PendingAction> pending = actions.pending();
        assertEquals(both.get(1).id(), pending.get(0).id(), "the soonest attempt comes first");
    }

    @Test
    void isEmptyWhenNothingIsPending() throws SQLException {
        assertTrue(actions.pending().isEmpty());
    }
}
