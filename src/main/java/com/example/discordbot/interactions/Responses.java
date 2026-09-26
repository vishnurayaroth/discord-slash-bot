package com.example.discordbot.interactions;

/**
 * The exact JSON bodies the interactions endpoint returns (contracts/interactions-endpoint.md).
 * Flag 64 makes a message private to the member who ran the command; Discord fixes that at the
 * first acknowledgement (research.md, Verified facts).
 */
public final class Responses {

    private Responses() {}

    /** Answer to Discord's verification PING. */
    public static String pong() {
        return "{\"type\":1}";
    }

    /** Private deferred acknowledgement: "thinking..." for the member, real reply follows. */
    public static String deferredPrivate() {
        return "{\"type\":5,\"data\":{\"flags\":64}}";
    }

    /** Private refusal for a command that could not be recorded in time (FR-026). */
    public static String tryAgain() {
        return "{\"type\":4,\"data\":{\"content\":\"Temporarily unavailable, please try again.\",\"flags\":64}}";
    }
}
