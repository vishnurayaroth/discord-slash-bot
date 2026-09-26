package com.example.discordbot.persistence;

import com.example.discordbot.config.Timing;
import com.example.discordbot.discord.Interaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The record step (research.md R2, data-model.md): one short transaction that reads the
 * settings, lets the planner decide, inserts the interaction with duplicate detection, inserts
 * its actions, and commits only if the gate still allows it.
 */
public final class InteractionStore implements Recorder {

    private static final String SNAPSHOT_SQL = """
            SELECT s.guild_id, s.channel_id, c.enabled, c.reply_text
            FROM (SELECT ?::text AS cmd) x
            LEFT JOIN command_configs c ON c.command = x.cmd
            LEFT JOIN server_connection s ON true""";

    private static final String INSERT_INTERACTION_SQL = """
            INSERT INTO interactions
              (id, guild_id, channel_id, member_id, member_name, command, text, priority, outcome,
               interaction_token, token_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + (?::int * interval '1 second'))
            ON CONFLICT (id) DO NOTHING""";

    private static final String INSERT_ACTION_SQL =
            "INSERT INTO actions (interaction_id, kind, status, payload, attempts, next_attempt_at) "
                    + "VALUES (?, ?, 'pending', ?, 0, now())";

    private final Database db;

    public InteractionStore(Database db) {
        this.db = db;
    }

    @Override
    public Outcome record(Interaction interaction, Planner planner, CommitPermit permit) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                Snapshot snapshot = readSnapshot(c, interaction.command());
                Plan plan = planner.plan(interaction, snapshot);
                if (!insertInteraction(c, interaction, plan)) {
                    c.rollback();
                    return Outcome.DUPLICATE;
                }
                insertActions(c, interaction.id(), plan);
                if (!permit.tryBeginCommit()) {
                    c.rollback();
                    return Outcome.ABANDONED;
                }
                c.commit();
                return Outcome.RECORDED;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the connection may already be gone; the original failure is what matters
                }
                throw e;
            }
        }
    }

    /** Removes the interaction token once the reply is finished or can no longer be sent. */
    public void clearToken(String interactionId) throws SQLException {
        try (Connection c = db.connection();
                PreparedStatement ps = db.prepare(c, "UPDATE interactions SET interaction_token = NULL WHERE id = ?")) {
            ps.setString(1, interactionId);
            ps.executeUpdate();
        }
    }

    private Snapshot readSnapshot(Connection c, String command) throws SQLException {
        try (PreparedStatement ps = db.prepare(c, SNAPSHOT_SQL)) {
            ps.setString(1, command);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                boolean enabledNull = rs.getObject(3) == null;
                return new Snapshot(rs.getString(1), rs.getString(2), enabledNull ? null : rs.getBoolean(3), rs.getString(4));
            }
        }
    }

    private boolean insertInteraction(Connection c, Interaction i, Plan plan) throws SQLException {
        try (PreparedStatement ps = db.prepare(c, INSERT_INTERACTION_SQL)) {
            ps.setString(1, i.id());
            ps.setString(2, i.guildId());
            ps.setString(3, i.channelId());
            ps.setString(4, orDefault(i.memberId(), "unknown"));
            ps.setString(5, orDefault(i.memberName(), "unknown"));
            ps.setString(6, i.command());
            ps.setString(7, i.text());
            ps.setBoolean(8, plan.priority());
            ps.setString(9, plan.outcome());
            ps.setString(10, i.token());
            ps.setInt(11, (int) Timing.TOKEN_LIFETIME.toSeconds());
            return ps.executeUpdate() == 1;
        }
    }

    private void insertActions(Connection c, String interactionId, Plan plan) throws SQLException {
        if (plan.actions().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = db.prepare(c, INSERT_ACTION_SQL)) {
            for (NewAction action : plan.actions()) {
                ps.setString(1, interactionId);
                ps.setString(2, action.kind());
                ps.setString(3, action.payload());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
