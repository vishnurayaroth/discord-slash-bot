package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerConnectionStoreTest {

    private Database db;
    private ServerConnectionStore store;

    @BeforeEach
    void fresh() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        store = new ServerConnectionStore(db);
    }

    @Test
    void saveCreatesTheSingleRow() throws SQLException {
        assertTrue(store.get().isEmpty());
        store.save("g1", "Alpha", "c1", "general");
        ServerConnection c = store.get().orElseThrow();
        assertEquals("g1", c.guildId());
        assertEquals("Alpha", c.guildName());
        assertEquals("c1", c.channelId());
        assertEquals("general", c.channelName());
    }

    @Test
    void savingAgainReplacesTheRowInsteadOfAddingOne() throws SQLException {
        store.save("g1", "Alpha", "c1", "general");
        var first = store.get().orElseThrow().connectedAt();
        store.save("g1", "Alpha", "c9", "reports");
        ServerConnection c = store.get().orElseThrow();
        assertEquals("c9", c.channelId());
        assertEquals("reports", c.channelName());
        assertFalse(c.connectedAt().isBefore(first), "connected_at moves forward when the channel changes");
        try (Connection conn = db.connection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM server_connection")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}
