package com.example.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InteractionParseTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesAReportCommandFromAServer() throws IOException {
        Interaction i = Interaction.parse(b("""
                {"id":"1001","application_id":"app1","type":2,"token":"tok","guild_id":"g1","channel_id":"c1",
                 "member":{"user":{"id":"u1","username":"alice","extra":"ignored"}},
                 "data":{"name":"report","options":[{"name":"text","type":3,"value":"printer on fire, urgent"}]},
                 "unknown_future_field":{"a":1}}"""));
        assertEquals("1001", i.id());
        assertEquals(2, i.type());
        assertEquals("tok", i.token());
        assertEquals("app1", i.applicationId());
        assertEquals("g1", i.guildId());
        assertEquals("c1", i.channelId());
        assertEquals("u1", i.memberId());
        assertEquals("alice", i.memberName());
        assertEquals("report", i.command());
        assertEquals("printer on fire, urgent", i.text());
    }

    @Test
    void statusHasNoText() throws IOException {
        Interaction i = Interaction.parse(b("""
                {"id":"1","type":2,"member":{"user":{"id":"u","username":"bob"}},"data":{"name":"status"}}"""));
        assertEquals("status", i.command());
        assertNull(i.text());
    }

    @Test
    void directMessageUserIsReadFromTheTopLevel() throws IOException {
        Interaction i = Interaction.parse(b("""
                {"id":"2","type":2,"user":{"id":"u9","username":"carol"},"data":{"name":"status"}}"""));
        assertEquals("u9", i.memberId());
        assertEquals("carol", i.memberName());
        assertNull(i.guildId());
    }

    @Test
    void pingHasOnlyAType() throws IOException {
        Interaction i = Interaction.parse(b("{\"type\":1}"));
        assertEquals(1, i.type());
        assertNull(i.id());
        assertNull(i.command());
    }

    @Test
    void invalidJsonOrNonObjectIsRejected() {
        assertThrows(IOException.class, () -> Interaction.parse(b("not json")));
        assertThrows(IOException.class, () -> Interaction.parse(b("[1,2,3]")));
    }
}
