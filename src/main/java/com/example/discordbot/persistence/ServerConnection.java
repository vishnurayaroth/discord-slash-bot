package com.example.discordbot.persistence;

import java.time.Instant;

/** The single connected Discord server and the channel the bot posts to. */
public record ServerConnection(String guildId, String guildName, String channelId, String channelName, Instant connectedAt) {}
