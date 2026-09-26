package com.example.discordbot.persistence;

import com.example.discordbot.discord.Interaction;
import java.sql.SQLException;

/**
 * Records one interaction and its actions atomically. An interface so the gate and its tests do
 * not depend on the database class.
 */
public interface Recorder {

    enum Outcome {
        /** Committed: the command is accepted. */
        RECORDED,
        /** The interaction id already existed: nothing written. */
        DUPLICATE,
        /** The permit was refused: rolled back, nothing written. */
        ABANDONED
    }

    Outcome record(Interaction interaction, Planner planner, CommitPermit permit) throws SQLException;
}
