package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InteractionStoreTest {

    private Database db;
    private InteractionStore store;

    @BeforeEach
    void fresh() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        store = new InteractionStore(db);
    }

    private static Interaction interaction(String id, String command, String text) {
        return new Interaction(id, 2, "secret-token", "app1", "g1", "c1", "u1", "alice", command, text);
    }

    private static final Planner TWO_ACTIONS = (i, s) -> new Plan("handled", false,
            List.of(new NewAction("reply", "hello"), new NewAction("mirror", "note")));

    private long count(String sql) throws SQLException {
        try (Connection c = db.connection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void recordsTheInteractionAndItsActionsInOneCommit() throws SQLException {
        assertEquals(Recorder.Outcome.RECORDED, store.record(interaction("i1", "status", null), TWO_ACTIONS, () -> true));
        assertEquals(1, count("SELECT count(*) FROM interactions WHERE id = 'i1' AND outcome = 'handled'"));
        assertEquals(2, count("SELECT count(*) FROM actions WHERE interaction_id = 'i1' AND status = 'pending'"));
        assertEquals(1, count("SELECT count(*) FROM interactions WHERE interaction_token = 'secret-token'"),
                "the token is stored so the reply can be retried after a restart");
        assertEquals(1, count("SELECT count(*) FROM interactions WHERE token_expires_at > now() + interval '14 minutes'"));
    }

    /** SC-003: the same interaction delivered five times leaves one record and one set of actions. */
    @Test
    void theSameInteractionFiveTimesLeavesOneRecordAndOneSetOfActions() throws SQLException {
        int recorded = 0;
        int duplicates = 0;
        for (int i = 0; i < 5; i++) {
            Recorder.Outcome outcome = store.record(interaction("same", "status", null), TWO_ACTIONS, () -> true);
            if (outcome == Recorder.Outcome.RECORDED) recorded++;
            if (outcome == Recorder.Outcome.DUPLICATE) duplicates++;
        }
        assertEquals(1, recorded);
        assertEquals(4, duplicates);
        assertEquals(1, count("SELECT count(*) FROM interactions"));
        assertEquals(2, count("SELECT count(*) FROM actions"));
    }

    @Test
    void aRefusedPermitRollsEverythingBack() throws SQLException {
        Recorder.Outcome outcome = store.record(interaction("late", "status", null), TWO_ACTIONS, () -> false);
        assertEquals(Recorder.Outcome.ABANDONED, outcome);
        assertEquals(0, count("SELECT count(*) FROM interactions"));
        assertEquals(0, count("SELECT count(*) FROM actions"));
    }

    @Test
    void aPlannerFailureLeavesNothingBehind() {
        Planner broken = (i, s) -> {
            throw new IllegalStateException("boom");
        };
        assertThrows(IllegalStateException.class, () -> store.record(interaction("x", "status", null), broken, () -> true));
        assertDoesNotHaveRows();
    }

    private void assertDoesNotHaveRows() {
        try {
            assertEquals(0, count("SELECT count(*) FROM interactions"));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theSnapshotReflectsTheConnectionAndTheCommandSettings() throws SQLException {
        AtomicReference<Snapshot> seen = new AtomicReference<>();
        Planner capture = (i, s) -> {
            seen.set(s);
            return new Plan("handled", false, List.of());
        };

        store.record(interaction("a", "status", null), capture, () -> true);
        assertNull(seen.get().connectedGuildId(), "no server connected yet");
        assertEquals(Boolean.TRUE, seen.get().commandEnabled());
        assertEquals("The service is operating.", seen.get().replyText());

        store.record(interaction("b", "nonsense", null), capture, () -> true);
        assertNull(seen.get().commandEnabled(), "an unknown command has no settings row");

        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                    + "VALUES (1, 'g1', 'Guild', 'chan1', 'general')");
            st.execute("UPDATE command_configs SET enabled = false WHERE command = 'report'");
        }
        store.record(interaction("c", "report", "hi"), capture, () -> true);
        assertEquals("g1", seen.get().connectedGuildId());
        assertEquals("chan1", seen.get().connectedChannelId());
        assertEquals(Boolean.FALSE, seen.get().commandEnabled());
    }

    @Test
    void clearTokenRemovesTheCredential() throws SQLException {
        store.record(interaction("t", "status", null), TWO_ACTIONS, () -> true);
        store.clearToken("t");
        assertEquals(0, count("SELECT count(*) FROM interactions WHERE interaction_token IS NOT NULL"));
        assertFalse(count("SELECT count(*) FROM interactions") == 0, "only the token is cleared");
    }
}
