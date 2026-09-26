package com.example.discordbot.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads actions and records their attempt results. Every update sets {@code updated_at}: the
 * timing check in task T098 reads it as the final-reply time.
 */
public final class ActionStore {

    private static final String SELECT = """
            SELECT a.id, a.interaction_id, a.kind, a.payload, a.attempts, a.status,
                   i.interaction_token, i.token_expires_at, i.received_at, a.next_attempt_at
            FROM actions a JOIN interactions i ON i.id = a.interaction_id""";

    private final Database db;

    public ActionStore(Database db) {
        this.db = db;
    }

    public Optional<PendingAction> load(long actionId) throws SQLException {
        List<PendingAction> found = query(SELECT + " WHERE a.id = ?", ps -> ps.setLong(1, actionId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public List<PendingAction> loadByInteraction(String interactionId) throws SQLException {
        return query(SELECT + " WHERE a.interaction_id = ? ORDER BY a.id", ps -> ps.setString(1, interactionId));
    }

    /** Every action still waiting for an attempt; used by the start-up recovery scan. */
    public List<PendingAction> pending() throws SQLException {
        return query(SELECT + " WHERE a.status = 'pending' ORDER BY a.next_attempt_at, a.id", ps -> { });
    }

    public void markSucceeded(long actionId, int attempts) throws SQLException {
        update("UPDATE actions SET status = 'succeeded', attempts = ?, last_error = NULL, updated_at = now() WHERE id = ?",
                ps -> {
                    ps.setInt(1, attempts);
                    ps.setLong(2, actionId);
                });
    }

    public void markRetry(long actionId, int attempts, Instant nextAttemptAt, String lastError) throws SQLException {
        update("UPDATE actions SET attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = now() WHERE id = ?",
                ps -> {
                    ps.setInt(1, attempts);
                    ps.setTimestamp(2, Timestamp.from(nextAttemptAt));
                    ps.setString(3, lastError);
                    ps.setLong(4, actionId);
                });
    }

    public void markFailed(long actionId, int attempts, String lastError) throws SQLException {
        update("UPDATE actions SET status = 'failed', attempts = ?, last_error = ?, updated_at = now() WHERE id = ?",
                ps -> {
                    ps.setInt(1, attempts);
                    ps.setString(2, lastError);
                    ps.setLong(3, actionId);
                });
    }

    // ---- helpers -----------------------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<PendingAction> query(String sql, Binder binder) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = db.prepare(c, sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingAction> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new PendingAction(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getString(7),
                            instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10))));
                }
                return out;
            }
        }
    }

    private void update(String sql, Binder binder) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = db.prepare(c, sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
