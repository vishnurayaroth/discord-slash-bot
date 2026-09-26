package com.example.discordbot.interactions;

import com.example.discordbot.config.Timing;
import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.NewAction;
import com.example.discordbot.persistence.Plan;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides what a command means (contracts/interactions-endpoint.md, "Outcome decided at record
 * time"). A pure function of the interaction and the snapshot: no database access, so it runs
 * inside the record transaction. Member-supplied text only ever goes inside message content.
 */
public final class CommandRules implements Planner {

    static final String NOT_CONFIGURED = "The service is not set up yet.";
    static final String WRONG_SERVER = "The service is not set up for this server.";
    static final String UNSUPPORTED = "That command is not supported.";
    static final String DISABLED = "This command is currently unavailable.";
    static final String PRIORITY_SUFFIX = " Flagged HIGH PRIORITY.";
    static final String PRIORITY_PREFIX = "[HIGH PRIORITY] ";

    @Override
    public Plan plan(Interaction interaction, Snapshot snapshot) {
        if (snapshot.connectedGuildId() == null) {
            return replyOnly("not_configured", NOT_CONFIGURED);
        }
        if (!snapshot.connectedGuildId().equals(interaction.guildId())) {
            return replyOnly("wrong_server", WRONG_SERVER);
        }
        if (snapshot.commandEnabled() == null) {
            return replyOnly("unsupported", UNSUPPORTED);
        }
        if (!snapshot.commandEnabled()) {
            return replyOnly("disabled", DISABLED);
        }

        boolean report = "report".equals(interaction.command());
        boolean priority = report && containsUrgent(interaction.text());
        String member = interaction.memberName() == null ? "someone" : interaction.memberName();

        String reply = snapshot.replyText() + (priority ? PRIORITY_SUFFIX : "");
        String note = report
                ? (priority ? PRIORITY_PREFIX : "") + "/report from " + member + ": " + nullToEmpty(interaction.text())
                : "/status was run by " + member + ".";

        List<NewAction> actions = new ArrayList<>();
        actions.add(new NewAction("reply", limit(reply)));
        if (report) {
            actions.add(new NewAction("post", limit(note)));
        }
        actions.add(new NewAction("mirror", limit(note)));
        return new Plan("handled", priority, actions);
    }

    private static Plan replyOnly(String outcome, String reply) {
        return new Plan(outcome, false, List.of(new NewAction("reply", reply)));
    }

    /** "urgent" in any letter case, also inside a longer word such as "urgently" (FR-003). */
    static boolean containsUrgent(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("urgent");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Defensive cut to Discord's message limit; the client truncates again. */
    private static String limit(String text) {
        int max = Timing.MAX_MESSAGE_CHARS;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
