package com.example.discordbot.interactions;

/**
 * Starts the recorded actions of one interaction (reply, post, mirror). Must not block: it only
 * schedules work. Called after the acknowledgement is written, never before.
 */
@FunctionalInterface
public interface ActionStarter {
    void start(String interactionId);
}
