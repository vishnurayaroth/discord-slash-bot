package com.example.discordbot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.persistence.LogEntry;
import com.example.discordbot.persistence.LogEntry.LogAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class LogViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LogEntry entry(String text, LogAction... actions) {
        return new LogEntry("1234567890123456789", Instant.parse("2026-09-27T10:15:30Z"), "alice", "report", text,
                true, "handled", new ArrayList<>(List.of(actions)));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            names.add(it.next());
        }
        return names;
    }

    @Test
    void jsonHasExactlyTheFieldsOfTheContract() throws Exception {
        JsonNode root = MAPPER.readTree(LogView.toJson(List.of(entry("hi",
                new LogAction("reply", "succeeded", 1, null)))));
        JsonNode e = root.get("entries").get(0);
        assertEquals(Set.of("id", "receivedAt", "member", "command", "text", "priority", "outcome", "overall", "actions"),
                fieldNames(e));
        assertEquals(Set.of("kind", "status", "attempts", "lastError"), fieldNames(e.get("actions").get(0)));
        assertEquals("2026-09-27T10:15:30Z", e.get("receivedAt").asText());
        assertTrue(e.get("priority").asBoolean());
    }

    @Test
    void overallIsDerivedFromTheActions() {
        assertEquals("complete", LogView.overall(entry("x",
                new LogAction("reply", "succeeded", 1, null), new LogAction("mirror", "succeeded", 1, null))));
        assertEquals("in_progress", LogView.overall(entry("x",
                new LogAction("reply", "succeeded", 1, null), new LogAction("mirror", "pending", 2, "HTTP 503"))));
        assertEquals("failed", LogView.overall(entry("x",
                new LogAction("reply", "pending", 1, null), new LogAction("mirror", "failed", 20, "retry limit reached"))));
        assertEquals("complete", LogView.overall(entry("x")), "no actions means nothing outstanding");
    }

    @Test
    void neverContainsTokensAddressesOrCredentials() {
        // LogEntry has no such fields, so even hostile-looking member text cannot smuggle them in via structure.
        String json = LogView.toJson(List.of(entry("text",
                new LogAction("mirror", "pending", 3, "HTTP 503"))));
        for (String forbidden : List.of("interaction_token", "interactionToken", "token", "webhook", "password", "Authorization")) {
            assertFalse(json.toLowerCase().contains(forbidden.toLowerCase()), "JSON mentions " + forbidden);
        }
    }

    @Test
    void markupInMemberTextIsPreservedAsPlainData() throws Exception {
        String hostile = "<script>alert(1)</script> & \"quotes\"  ";
        JsonNode e = MAPPER.readTree(LogView.toJson(List.of(entry(hostile)))).get("entries").get(0);
        assertEquals(hostile, e.get("text").asText(), "data is stored and returned verbatim; renderers escape it");
    }

    @Test
    void anEmptyLogIsAnEmptyArray() throws Exception {
        assertEquals(0, MAPPER.readTree(LogView.toJson(List.of())).get("entries").size());
    }
}
