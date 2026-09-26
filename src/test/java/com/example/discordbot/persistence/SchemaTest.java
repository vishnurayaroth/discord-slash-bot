package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaTest {

    private Database db;

    @BeforeEach
    void fresh() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void interaction(String id) throws SQLException {
        exec("INSERT INTO interactions (id, member_id, member_name, command, outcome) "
                + "VALUES ('" + id + "', 'u1', 'alice', 'status', 'handled')");
    }

    @Test
    void scriptRunsTwiceWithoutError() throws SQLException {
        db.applySchema();
        db.applySchema();
        try (Connection c = db.connection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM command_configs")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "default rows are inserted once, not duplicated");
        }
    }

    @Test
    void duplicateActionKindForOneInteractionIsRejected() throws SQLException {
        interaction("i1");
        exec("INSERT INTO actions (interaction_id, kind, payload) VALUES ('i1', 'reply', 'x')");
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO actions (interaction_id, kind, payload) VALUES ('i1', 'reply', 'y')"));
    }

    @Test
    void blankReplyTextIsRejected() {
        assertThrows(SQLException.class,
                () -> exec("UPDATE command_configs SET reply_text = '   ' WHERE command = 'status'"));
    }

    @Test
    void onlyOneServerConnectionRowIsAllowed() throws SQLException {
        exec("INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                + "VALUES (1, 'g', 'G', 'c', 'C')");
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                        + "VALUES (2, 'g2', 'G2', 'c2', 'C2')"));
    }

    @Test
    void orphanActionIsRejected() {
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO actions (interaction_id, kind, payload) VALUES ('missing', 'reply', 'x')"));
    }

    @Test
    void duplicateInteractionIdIsRejected() throws SQLException {
        interaction("dup");
        assertThrows(SQLException.class, () -> interaction("dup"));
    }
}
