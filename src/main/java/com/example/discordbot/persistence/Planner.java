package com.example.discordbot.persistence;

import com.example.discordbot.discord.Interaction;

/** A pure function: no database access, so it can run inside the record transaction. */
@FunctionalInterface
public interface Planner {
    Plan plan(Interaction interaction, Snapshot snapshot);
}
