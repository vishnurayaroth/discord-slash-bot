package com.example.discordbot.dashboard;

import com.example.discordbot.persistence.CommandConfig;
import com.example.discordbot.persistence.CommandConfigStore;
import java.sql.SQLException;
import java.util.List;

/**
 * Validates and saves command settings (User Story 6). An empty reply text is refused and the old
 * value stays; an unknown command is refused; the last saved edit wins.
 */
public final class ConfigService {

    /** The result of a save: {@code ok} and a message for the page. */
    public record Result(boolean ok, String message) {}

    private final CommandConfigStore store;

    public ConfigService(CommandConfigStore store) {
        this.store = store;
    }

    public List<CommandConfig> all() throws SQLException {
        return store.all();
    }

    public Result save(String command, String enabledParameter, String replyText) {
        if (command == null || command.isBlank()) {
            return new Result(false, "Unknown command.");
        }
        Boolean enabled = parseEnabled(enabledParameter);
        if (enabled == null) {
            return new Result(false, "Choose Enabled or Disabled. Nothing was changed.");
        }
        if (replyText == null || replyText.isBlank()) {
            return new Result(false, "The reply text cannot be empty. The previous value was kept.");
        }
        try {
            if (!store.update(command, enabled, replyText.trim())) {
                return new Result(false, "Unknown command.");
            }
        } catch (SQLException e) {
            return new Result(false, "The setting could not be saved right now. Please try again.");
        }
        return new Result(true, "Saved /" + command + ": " + (enabled ? "enabled" : "disabled") + ".");
    }

    private static Boolean parseEnabled(String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
