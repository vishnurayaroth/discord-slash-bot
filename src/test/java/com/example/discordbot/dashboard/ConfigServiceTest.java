package com.example.discordbot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.interactions.CommandRules;
import com.example.discordbot.persistence.CommandConfigStore;
import com.example.discordbot.persistence.Database;
import com.example.discordbot.persistence.InteractionStore;
import com.example.discordbot.persistence.PostgresTestSupport;
import com.example.discordbot.persistence.Recorder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigServiceTest {

    private Database db;
    private CommandConfigStore store;
    private ConfigService service;
    private InteractionStore interactions;

    @BeforeEach
    void setUp() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        store = new CommandConfigStore(db);
        service = new ConfigService(store);
        interactions = new InteractionStore(db);
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                    + "VALUES (1, 'g1', 'Guild', 'chan1', 'reports')");
        }
    }

    private String outcomeOfNextReport(String id) throws SQLException {
        Recorder.Outcome recorded = interactions.record(
                new Interaction(id, 2, "tok", "app", "g1", "c1", "u1", "alice", "report", "hello"),
                new CommandRules(), () -> true);
        assertEquals(Recorder.Outcome.RECORDED, recorded);
        try (Connection c = db.connection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT outcome FROM interactions WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void anEmptyOrBlankReplyIsRefusedAndTheOldValueStays() throws SQLException {
        String before = store.find("status").orElseThrow().replyText();
        for (String bad : new String[] {"", "   ", null}) {
            ConfigService.Result r = service.save("status", "true", bad);
            assertFalse(r.ok());
            assertTrue(r.message().contains("cannot be empty"));
        }
        assertEquals(before, store.find("status").orElseThrow().replyText());
    }

    @Test
    void anUnknownCommandIsRefused() {
        assertFalse(service.save("nonsense", "true", "hi").ok());
        assertFalse(service.save(null, "true", "hi").ok());
        assertFalse(service.save("", "true", "hi").ok());
    }

    @Test
    void anInvalidEnabledValueIsRefusedAndNothingChanges() throws SQLException {
        assertFalse(service.save("status", "maybe", "New text").ok());
        assertFalse(service.save("status", null, "New text").ok());
        assertEquals("The service is operating.", store.find("status").orElseThrow().replyText());
    }

    @Test
    void theLastSavedEditWins() throws SQLException {
        assertTrue(service.save("report", "true", "First").ok());
        assertTrue(service.save("report", "false", "Second").ok());
        var saved = store.find("report").orElseThrow();
        assertEquals("Second", saved.replyText());
        assertFalse(saved.enabled());
    }

    @Test
    void theReplyTextIsTrimmedAndKeptVerbatimOtherwise() throws SQLException {
        assertTrue(service.save("status", "true", "  All good <b>here</b> & there  ").ok());
        assertEquals("All good <b>here</b> & there", store.find("status").orElseThrow().replyText());
    }

    /** SC-009: a change takes effect on the very next command, with no restart. */
    @Test
    void disablingACommandTakesEffectOnTheNextCommand() throws SQLException {
        assertEquals("handled", outcomeOfNextReport("before"));
        assertTrue(service.save("report", "false", "Any text").ok());
        assertEquals("disabled", outcomeOfNextReport("after"));
        assertTrue(service.save("report", "true", "Any text").ok());
        assertEquals("handled", outcomeOfNextReport("again"));
    }

    @Test
    void aNewReplyTextIsUsedByTheNextCommand() throws SQLException {
        assertTrue(service.save("report", "true", "We got it, thanks.").ok());
        outcomeOfNextReport("next");
        try (Connection c = db.connection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT payload FROM actions WHERE interaction_id = 'next' AND kind = 'reply'")) {
            rs.next();
            assertEquals("We got it, thanks.", rs.getString(1));
        }
    }

    @Test
    void listsBothCommandsWithTheirSettings() throws SQLException {
        assertEquals(2, service.all().size());
    }
}
