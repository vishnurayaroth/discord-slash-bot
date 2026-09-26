package com.example.discordbot.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pongIsTypeOne() throws Exception {
        JsonNode n = MAPPER.readTree(Responses.pong());
        assertEquals(1, n.get("type").asInt());
    }

    @Test
    void deferredAcknowledgementIsTypeFiveAndPrivate() throws Exception {
        JsonNode n = MAPPER.readTree(Responses.deferredPrivate());
        assertEquals(5, n.get("type").asInt());
        assertEquals(64, n.get("data").get("flags").asInt());
    }

    @Test
    void tryAgainIsADirectPrivateMessageNotADeferral() throws Exception {
        JsonNode n = MAPPER.readTree(Responses.tryAgain());
        assertEquals(4, n.get("type").asInt());
        assertEquals("Temporarily unavailable, please try again.", n.get("data").get("content").asText());
        assertEquals(64, n.get("data").get("flags").asInt());
        assertFalse(n.get("data").has("components"));
    }
}
