package com.example.discordbot.dashboard;

import com.example.discordbot.persistence.LogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Builds the JSON of GET /api/log (contracts/dashboard-http.md). Only the fields of
 * {@link LogEntry} exist, so tokens and addresses cannot appear. {@code member} and {@code text}
 * are untrusted data: escaping is the job of the renderer (textContent in the script).
 */
public final class LogView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LogView() {}

    public static String toJson(List<LogEntry> entries) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("entries");
        for (LogEntry e : entries) {
            ObjectNode n = array.addObject();
            n.put("id", e.id());
            n.put("receivedAt", e.receivedAt().toString());
            n.put("member", e.member());
            n.put("command", e.command());
            n.put("text", e.text());
            n.put("priority", e.priority());
            n.put("outcome", e.outcome());
            n.put("overall", overall(e));
            ArrayNode actions = n.putArray("actions");
            for (LogEntry.LogAction a : e.actions()) {
                ObjectNode an = actions.addObject();
                an.put("kind", a.kind());
                an.put("status", a.status());
                an.put("attempts", a.attempts());
                an.put("lastError", a.lastError());
            }
        }
        return root.toString();
    }

    /** complete (all succeeded), in_progress (any pending), failed (any permanently failed). */
    static String overall(LogEntry entry) {
        boolean pending = false;
        for (LogEntry.LogAction a : entry.actions()) {
            if ("failed".equals(a.status())) {
                return "failed";
            }
            if ("pending".equals(a.status())) {
                pending = true;
            }
        }
        return pending ? "in_progress" : "complete";
    }
}
