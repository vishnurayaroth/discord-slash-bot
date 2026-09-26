package com.example.discordbot.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.NewAction;
import com.example.discordbot.persistence.Plan;
import com.example.discordbot.persistence.Snapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRulesTest {

    private final CommandRules rules = new CommandRules();
    private static final Snapshot READY = new Snapshot("g1", "chan1", Boolean.TRUE, "Thanks, your report was received.");

    private static Interaction in(String command, String text, String guild) {
        return new Interaction("id", 2, "tok", "app", guild, "c1", "u1", "alice", command, text);
    }

    private static List<String> kinds(Plan plan) {
        return plan.actions().stream().map(NewAction::kind).toList();
    }

    private static String payload(Plan plan, String kind) {
        return plan.actions().stream().filter(a -> a.kind().equals(kind)).findFirst().orElseThrow().payload();
    }

    @Test
    void statusIsHandledWithReplyAndMirrorOnly() {
        Plan p = rules.plan(in("status", null, "g1"), new Snapshot("g1", "chan1", true, "The service is operating."));
        assertEquals("handled", p.outcome());
        assertFalse(p.priority());
        assertEquals(List.of("reply", "mirror"), kinds(p));
        assertEquals("The service is operating.", payload(p, "reply"));
        assertEquals("/status was run by alice.", payload(p, "mirror"));
    }

    @Test
    void reportIsHandledWithReplyPostAndMirror() {
        Plan p = rules.plan(in("report", "hello", "g1"), READY);
        assertEquals("handled", p.outcome());
        assertEquals(List.of("reply", "post", "mirror"), kinds(p));
        assertEquals("/report from alice: hello", payload(p, "post"));
        assertEquals(payload(p, "post"), payload(p, "mirror"));
        assertFalse(p.priority());
    }

    @Test
    void urgentInAnyLetterCaseFlagsTheReportEverywhere() {
        for (String text : List.of("this is urgent", "URGENT!", "Urgent care", "please act urgently")) {
            Plan p = rules.plan(in("report", text, "g1"), READY);
            assertTrue(p.priority(), text);
            assertTrue(payload(p, "reply").endsWith(" Flagged HIGH PRIORITY."), text);
            assertTrue(payload(p, "post").startsWith("[HIGH PRIORITY] "), text);
            assertTrue(payload(p, "mirror").startsWith("[HIGH PRIORITY] "), text);
        }
    }

    @Test
    void anOrdinaryReportIsNotFlaggedAnywhere() {
        Plan p = rules.plan(in("report", "the printer is out of paper", "g1"), READY);
        assertFalse(p.priority());
        assertFalse(payload(p, "reply").contains("HIGH PRIORITY"));
        assertFalse(payload(p, "post").contains("HIGH PRIORITY"));
        assertFalse(payload(p, "mirror").contains("HIGH PRIORITY"));
    }

    @Test
    void statusIsNeverFlaggedEvenIfTheWordAppears() {
        Plan p = rules.plan(in("status", "urgent", "g1"), READY);
        assertFalse(p.priority());
    }

    @Test
    void noConnectedServerMeansNotConfiguredAndOnlyAReply() {
        Plan p = rules.plan(in("status", null, "g1"), new Snapshot(null, null, true, "x"));
        assertEquals("not_configured", p.outcome());
        assertEquals(List.of("reply"), kinds(p));
        assertEquals("The service is not set up yet.", payload(p, "reply"));
    }

    @Test
    void aDifferentServerIsWrongServer() {
        Plan p = rules.plan(in("status", null, "other-guild"), READY);
        assertEquals("wrong_server", p.outcome());
        assertEquals(List.of("reply"), kinds(p));
        assertEquals("The service is not set up for this server.", payload(p, "reply"));
    }

    @Test
    void anUnknownCommandIsUnsupported() {
        Plan p = rules.plan(in("nonsense", null, "g1"), new Snapshot("g1", "chan1", null, null));
        assertEquals("unsupported", p.outcome());
        assertEquals("That command is not supported.", payload(p, "reply"));
    }

    @Test
    void aDisabledCommandIsUnavailableWithNoMirrorOrPost() {
        Plan p = rules.plan(in("report", "hi", "g1"), new Snapshot("g1", "chan1", false, "x"));
        assertEquals("disabled", p.outcome());
        assertEquals(List.of("reply"), kinds(p));
        assertEquals("This command is currently unavailable.", payload(p, "reply"));
    }

    @Test
    void notConfiguredWinsOverAnUnknownCommand() {
        Plan p = rules.plan(in("nonsense", null, "g1"), new Snapshot(null, null, null, null));
        assertEquals("not_configured", p.outcome());
    }

    @Test
    void longTextIsCutToDiscordsLimitAndStaysInsideTheContent() {
        Plan p = rules.plan(in("report", "x".repeat(3000), "g1"), READY);
        assertEquals(2000, payload(p, "post").length());
        assertEquals(2000, payload(p, "mirror").length());
    }

    @Test
    void memberTextIsOnlyEverPlacedInsideMessageContent() {
        Plan p = rules.plan(in("report", "@everyone <script>alert(1)</script>", "g1"), READY);
        // Mentions are neutralised by allowed_mentions in the client; the rules add no markup of their own.
        assertEquals("/report from alice: @everyone <script>alert(1)</script>", payload(p, "post"));
    }
}
