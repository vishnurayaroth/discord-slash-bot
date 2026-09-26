package com.example.discordbot.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads command settings for the dashboard. The default rows for {@code status} and
 * {@code report} are inserted by schema.sql; the record step reads them through the snapshot.
 */
public final class CommandConfigStore {

    private static final String SELECT = "SELECT command, enabled, reply_text, updated_at FROM command_configs";

    private final Database db;

    public CommandConfigStore(Database db) {
        this.db = db;
    }

    public Optional<CommandConfig> find(String command) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = db.prepare(c, SELECT + " WHERE command = ?")) {
            ps.setString(1, command);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public List<CommandConfig> all() throws SQLException {
        try (Connection c = db.connection();
                PreparedStatement ps = db.prepare(c, SELECT + " ORDER BY command");
                ResultSet rs = ps.executeQuery()) {
            List<CommandConfig> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        }
    }

    /**
     * Saves one command's settings; the last saved edit wins. The next command reads the new values
     * in its own record transaction, so no restart or redeploy is needed (SC-009).
     *
     * @return false when there is no such command
     */
    public boolean update(String command, boolean enabled, String replyText) throws SQLException {
        try (Connection c = db.connection();
                PreparedStatement ps = db.prepare(c,
                        "UPDATE command_configs SET enabled = ?, reply_text = ?, updated_at = now() WHERE command = ?")) {
            ps.setBoolean(1, enabled);
            ps.setString(2, replyText);
            ps.setString(3, command);
            return ps.executeUpdate() == 1;
        }
    }

    private static CommandConfig read(ResultSet rs) throws SQLException {
        return new CommandConfig(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getTimestamp(4).toInstant());
    }
}
