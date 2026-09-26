package com.example.discordbot.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** The single-row server connection (data-model.md). */
public final class ServerConnectionStore {

    private final Database db;

    public ServerConnectionStore(Database db) {
        this.db = db;
    }

    public Optional<ServerConnection> get() throws SQLException {
        try (Connection c = db.connection();
                PreparedStatement ps = db.prepare(c,
                        "SELECT guild_id, guild_name, channel_id, channel_name, connected_at FROM server_connection WHERE id = 1");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new ServerConnection(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getTimestamp(5).toInstant()));
        }
    }
}
