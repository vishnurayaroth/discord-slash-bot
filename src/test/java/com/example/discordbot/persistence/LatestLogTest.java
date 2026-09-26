package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dashboard log query: newest first, limited, with each interaction's actions attached. */
class LatestLogTest {

    private InteractionStore store;
    private ActionStore actions;

    @BeforeEach
    void fresh() throws SQLException {
        Database db = PostgresTestSupport.freshDatabase();
        store = new InteractionStore(db);
        actions = new ActionStore(db);
    }

    private void record(String id, String text) throws SQLException {
        store.record(new Interaction(id, 2, "secret-token-" + id, "app", "g1", "c1", "u1", "alice", "report", text),
                (i, s) -> new Plan("handled", text.contains("urgent"), List.of(new NewAction("reply", "r"), new NewAction("mirror", "m"))),
                () -> true);
    }

    @Test
    void returnsNewestFirstWithActionsAndRespectsTheLimit() throws Exception {
        record("a", "first");
        Thread.sleep(15);
        record("b", "second urgent");
        Thread.sleep(15);
        record("c", "third");
        actions.markFailed(actions.loadByInteraction("b").get(1).id(), 4, "retry limit reached (last error: HTTP 503)");

        List<LogEntry> two = store.latest(2);
        assertEquals(List.of("c", "b"), two.stream().map(LogEntry::id).toList());

        LogEntry b = two.get(1);
        assertTrue(b.priority());
        assertEquals("alice", b.member());
        assertEquals("second urgent", b.text());
        assertEquals(2, b.actions().size());
        assertEquals("reply", b.actions().get(0).kind());
        assertEquals("failed", b.actions().get(1).status());
        assertEquals(4, b.actions().get(1).attempts());
        assertEquals("retry limit reached (last error: HTTP 503)", b.actions().get(1).lastError());
    }

    @Test
    void anEmptyDatabaseGivesAnEmptyList() throws SQLException {
        assertTrue(store.latest(50).isEmpty());
    }
}
