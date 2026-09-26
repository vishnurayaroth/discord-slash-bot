package com.example.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CommandDefinitionsTest {

    /** Discord's rule for chat-input command and option names: 1 to 32 lowercase word characters. */
    private static final Pattern NAME = Pattern.compile("^[-_\\p{L}\\p{N}]{1,32}$");

    private static final JsonNode COMMANDS = CommandDefinitions.tree();

    private static JsonNode command(String name) {
        for (JsonNode c : COMMANDS) {
            if (c.get("name").asText().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no command " + name);
    }

    @Test
    void definesExactlyStatusAndReport() {
        List<String> names = new ArrayList<>();
        COMMANDS.forEach(c -> names.add(c.get("name").asText()));
        assertEquals(List.of("status", "report"), names);
    }

    @Test
    void namesAndDescriptionsRespectDiscordsLimits() {
        for (JsonNode c : COMMANDS) {
            String name = c.get("name").asText();
            assertTrue(NAME.matcher(name).matches(), name);
            assertEquals(name.toLowerCase(), name, "names must be lowercase");
            int description = c.get("description").asText().length();
            assertTrue(description >= 1 && description <= 100, name + " description length " + description);
            assertEquals(1, c.get("type").asInt(), "chat-input command");
            JsonNode options = c.path("options");
            assertTrue(options.size() <= 25);
            boolean sawOptional = false;
            for (JsonNode o : options) {
                assertTrue(NAME.matcher(o.get("name").asText()).matches());
                int d = o.get("description").asText().length();
                assertTrue(d >= 1 && d <= 100);
                boolean required = o.path("required").asBoolean(false);
                assertFalse(sawOptional && required, "required options must come first");
                sawOptional |= !required;
            }
        }
    }

    @Test
    void statusHasNoOptions() {
        assertFalse(command("status").has("options"));
    }

    @Test
    void reportHasOneRequiredStringOptionCappedAtOneThousandCharacters() {
        JsonNode options = command("report").get("options");
        assertEquals(1, options.size());
        JsonNode text = options.get(0);
        assertEquals("text", text.get("name").asText());
        assertEquals(3, text.get("type").asInt(), "string option");
        assertTrue(text.get("required").asBoolean());
        assertEquals(1000, text.get("max_length").asInt());
    }

    @Test
    void jsonIsValidAndMatchesTheTree() throws Exception {
        assertEquals(COMMANDS, new ObjectMapper().readTree(CommandDefinitions.json()));
    }
}
