package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoresTest {

    private Database db;
    private InteractionStore interactions;
    private ActionStore actions;
    private CommandConfigStore configs;
    private ServerConnectionStore connections;

    @BeforeEach
    void fresh() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        interactions = new InteractionStore(db);
        actions = new ActionStore(db);
        configs = new CommandConfigStore(db);
        connections = new ServerConnectionStore(db);
    }

    private void record(String id) throws SQLException {
        interactions.record(new Interaction(id, 2, "tok", "app", "g1", "c1", "u1", "alice", "report", "hi"),
                (i, s) -> new Plan("handled", false,
                        List.of(new NewAction("reply", "r"), new NewAction("post", "p"), new NewAction("mirror", "m"))),
                () -> true);
    }

    @Test
    void defaultCommandSettingsExist() throws SQLException {
        assertEquals(2, configs.all().size());
        CommandConfig status = configs.find("status").orElseThrow();
        assertTrue(status.enabled());
        assertEquals("The service is operating.", status.replyText());
        assertTrue(configs.find("nope").isEmpty());
    }

    @Test
    void theConnectionRowIsAbsentUntilOneExists() throws SQLException {
        assertTrue(connections.get().isEmpty());
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                    + "VALUES (1, 'g1', 'My Guild', 'chan1', 'reports')");
        }
        ServerConnection connection = connections.get().orElseThrow();
        assertEquals("g1", connection.guildId());
        assertEquals("chan1", connection.channelId());
        assertEquals("reports", connection.channelName());
    }

    @Test
    void actionsAreCreatedWithTheInteractionAndLoadWithTheirToken() throws SQLException {
        record("i1");
        List<PendingAction> loaded = actions.loadByInteraction("i1");
        assertEquals(List.of("reply", "post", "mirror"), loaded.stream().map(PendingAction::kind).toList());
        PendingAction reply = loaded.get(0);
        assertEquals("pending", reply.status());
        assertEquals(0, reply.attempts());
        assertEquals("tok", reply.interactionToken());
        assertNotNull(reply.tokenExpiresAt());
        assertNotNull(reply.receivedAt());
    }

    @Test
    void actionsCanBeMarkedSucceededRetriedAndFailedAndEveryUpdateSetsUpdatedAt() throws Exception {
        record("i2");
        List<PendingAction> loaded = actions.loadByInteraction("i2");
        long reply = loaded.get(0).id();
        long post = loaded.get(1).id();
        long mirror = loaded.get(2).id();

        Instant before = Instant.now().minusSeconds(1);
        actions.markSucceeded(reply, 1);
        actions.markRetry(post, 2, Instant.now().plusSeconds(30), "HTTP 503");
        actions.markFailed(mirror, 3, "HTTP 404");

        PendingAction r = actions.load(reply).orElseThrow();
        assertEquals("succeeded", r.status());
        assertEquals(1, r.attempts());
        PendingAction p = actions.load(post).orElseThrow();
        assertEquals("pending", p.status());
        assertEquals(2, p.attempts());
        assertTrue(p.nextAttemptAt().isAfter(Instant.now().plusSeconds(20)));
        PendingAction m = actions.load(mirror).orElseThrow();
        assertEquals("failed", m.status());
        assertEquals(3, m.attempts());

        assertEquals(List.of(post), actions.pending().stream().map(PendingAction::id).toList(),
                "only the still-pending action is listed for recovery");
        assertFalse(updatedAt(reply).isBefore(before), "updated_at is set on every update");
        assertFalse(updatedAt(post).isBefore(before));
        assertFalse(updatedAt(mirror).isBefore(before));
    }

    private Instant updatedAt(long actionId) throws SQLException {
        try (Connection c = db.connection();
                java.sql.PreparedStatement ps = c.prepareStatement("SELECT updated_at FROM actions WHERE id = ?")) {
            ps.setLong(1, actionId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    @Test
    void anUnknownActionLoadsAsEmpty() throws SQLException {
        assertTrue(actions.load(999_999).isEmpty());
    }
}
