package com.example.discordbot.persistence;

/**
 * What the planner needs to decide an outcome, read in the same transaction that records the
 * interaction. A null {@code connectedGuildId} means no server is connected; a null
 * {@code commandEnabled} means the command has no configuration row (unknown command).
 */
public record Snapshot(String connectedGuildId, String connectedChannelId, Boolean commandEnabled, String replyText) {}
