package com.example.discordbot.dashboard;

import com.example.discordbot.discord.DiscordClient;
import com.example.discordbot.discord.DiscordClient.Channel;
import com.example.discordbot.discord.DiscordClient.Fetched;
import com.example.discordbot.discord.DiscordClient.Guild;
import com.example.discordbot.discord.DiscordResult;
import com.example.discordbot.persistence.ServerConnectionStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The logic behind the Connect page (User Story 5). Only ids that Discord itself just listed are
 * accepted. Order is fixed: prove the bot can post to the channel with a test message, then save,
 * then register the commands; nothing is saved if the test message fails (contracts/dashboard-http.md).
 */
public final class ConnectService {

    /** A list for the page, or an error message when Discord could not be asked. */
    public record Listing<T>(List<T> items, String error) {}

    /** The result of a connect attempt; {@code saved} says whether the connection row was written. */
    public record Outcome(boolean saved, boolean ok, String message) {}

    private final DiscordClient discord;
    private final ServerConnectionStore store;
    private final String applicationId;

    public ConnectService(DiscordClient discord, ServerConnectionStore store, String applicationId) {
        this.discord = discord;
        this.store = store;
        this.applicationId = applicationId;
    }

    /** The invite address: bot plus slash-command scopes, permission View Channel + Send Messages (3072). */
    public String inviteUrl() {
        return "https://discord.com/oauth2/authorize?client_id=" + applicationId
                + "&scope=bot%20applications.commands&permissions=3072";
    }

    public Listing<Guild> guilds() {
        Fetched<List<Guild>> fetched = discord.listGuilds();
        return fetched.result().isSuccess()
                ? new Listing<>(fetched.value(), null)
                : new Listing<>(List.of(), "Could not read the bot's servers from Discord (" + fetched.result().error() + ").");
    }

    public Listing<Channel> channels(String guildId) {
        Fetched<List<Channel>> fetched = discord.listChannels(guildId);
        return fetched.result().isSuccess()
                ? new Listing<>(fetched.value(), null)
                : new Listing<>(List.of(), "Could not read that server's channels from Discord (" + fetched.result().error() + ").");
    }

    public Outcome connect(String guildId, String channelId) {
        Listing<Guild> guilds = guilds();
        if (guilds.error() != null) {
            return new Outcome(false, false, guilds.error());
        }
        Optional<Guild> guild = guilds.items().stream().filter(g -> g.id().equals(guildId)).findFirst();
        if (guild.isEmpty()) {
            return new Outcome(false, false, "That server is not one the bot has been added to. Add the bot first, then refresh.");
        }
        Listing<Channel> channels = channels(guildId);
        if (channels.error() != null) {
            return new Outcome(false, false, channels.error());
        }
        Optional<Channel> channel = channels.items().stream().filter(c -> c.id().equals(channelId)).findFirst();
        if (channel.isEmpty()) {
            return new Outcome(false, false, "That channel is not a text channel of the selected server.");
        }

        DiscordResult test = discord.postMessage(channelId,
                "Bot connected. Reports from /report will be posted in this channel.");
        if (!test.isSuccess()) {
            return new Outcome(false, false, "The bot cannot post to #" + channel.get().name() + " (" + test.error()
                    + "). Check its permissions. Nothing was saved.");
        }

        try {
            store.save(guild.get().id(), guild.get().name(), channel.get().id(), channel.get().name());
        } catch (SQLException e) {
            return new Outcome(false, false, "The connection could not be saved right now. Please try again.");
        }

        DiscordResult registered = discord.registerCommands(applicationId, guildId);
        if (!registered.isSuccess()) {
            return new Outcome(true, false, "Connected to " + guild.get().name() + " / #" + channel.get().name()
                    + ", but registering the commands failed (" + registered.error() + "). Save again to retry.");
        }
        return new Outcome(true, true, "Connected to " + guild.get().name() + " / #" + channel.get().name()
                + ". The /status and /report commands are registered.");
    }
}
