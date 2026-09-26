package com.example.discordbot.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * The parts of a Discord interaction this service uses. Parsed from the raw body with Jackson;
 * unknown fields are ignored because Discord adds fields over time.
 */
public record Interaction(
        String id,
        int type,
        String token,
        String applicationId,
        String guildId,
        String channelId,
        String memberId,
        String memberName,
        String command,
        String text) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Interaction parse(byte[] json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("interaction is not a JSON object");
        }
        // In a server the user is under "member"; in a direct message it is top level.
        JsonNode user = root.path("member").path("user");
        if (user.isMissingNode()) {
            user = root.path("user");
        }
        return new Interaction(
                text(root, "id"),
                root.path("type").asInt(-1),
                text(root, "token"),
                text(root, "application_id"),
                text(root, "guild_id"),
                text(root, "channel_id"),
                text(user, "id"),
                text(user, "username"),
                text(root.path("data"), "name"),
                optionText(root.path("data").path("options")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String optionText(JsonNode options) {
        if (options.isArray()) {
            for (JsonNode option : options) {
                if ("text".equals(option.path("name").asText())) {
                    return text(option, "value");
                }
            }
        }
        return null;
    }
}
