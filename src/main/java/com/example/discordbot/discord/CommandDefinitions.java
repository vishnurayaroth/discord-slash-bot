package com.example.discordbot.discord;

import com.example.discordbot.config.Timing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The slash commands registered for the connected server (contracts/discord-outbound.md).
 * Names are lowercase; {@code report} has one required string option capped at 1000 characters
 * so composed messages stay under Discord's 2000-character limit.
 */
public final class CommandDefinitions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Discord's chat-input command type. */
    private static final int CHAT_INPUT = 1;
    /** Discord's string option type. */
    private static final int STRING = 3;

    private CommandDefinitions() {}

    public static JsonNode tree() {
        ArrayNode commands = MAPPER.createArrayNode();

        ObjectNode status = commands.addObject();
        status.put("name", "status");
        status.put("description", "Check that the service is running");
        status.put("type", CHAT_INPUT);

        ObjectNode report = commands.addObject();
        report.put("name", "report");
        report.put("description", "Send a report to the admins");
        report.put("type", CHAT_INPUT);
        ObjectNode text = report.putArray("options").addObject();
        text.put("name", "text");
        text.put("description", "What to report");
        text.put("type", STRING);
        text.put("required", true);
        text.put("max_length", Timing.REPORT_TEXT_MAX);
        return commands;
    }

    public static String json() {
        return tree().toString();
    }
}
