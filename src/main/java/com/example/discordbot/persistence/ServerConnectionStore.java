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

    /** Creates or replaces the single connection row. */
    public void save(String guildId, String guildName, String channelId, String channelName) throws SQLException {
        try (Connection c = db.connection();
                PreparedStatement ps = db.prepare(c, """
                        INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name)
                        VALUES (1, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET guild_id = EXCLUDED.guild_id, guild_name = EXCLUDED.guild_name,
                          channel_id = EXCLUDED.channel_id, channel_name = EXCLUDED.channel_name, connected_at = now()""")) {
            ps.setString(1, guildId);
            ps.setString(2, guildName);
            ps.setString(3, channelId);
            ps.setString(4, channelName);
            ps.executeUpdate();
        }
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
