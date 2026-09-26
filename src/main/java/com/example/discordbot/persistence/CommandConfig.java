package com.example.discordbot.persistence;

import java.time.Instant;

/** One command's admin-editable settings (spec: Command Configuration). */
public record CommandConfig(String command, boolean enabled, String replyText, Instant updatedAt) {}
