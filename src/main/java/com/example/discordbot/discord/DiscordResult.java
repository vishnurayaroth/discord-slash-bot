package com.example.discordbot.discord;

/**
 * Outcome of one outbound Discord call, already classified for retry decisions
 * (contracts/discord-outbound.md). The {@code error} text is built only from the status code
 * and Discord's numeric error code, never from the address, token, or response body
 * (constitution Principle IV).
 */
public record DiscordResult(Kind kind, int status, int code, double retryAfterSeconds, String error) {

    public enum Kind { SUCCESS, RETRYABLE, PERMANENT }

    public static DiscordResult success(int status) {
        return new DiscordResult(Kind.SUCCESS, status, 0, 0, null);
    }

    public static DiscordResult retryable(int status, int code, double retryAfterSeconds, String error) {
        return new DiscordResult(Kind.RETRYABLE, status, code, retryAfterSeconds, error);
    }

    public static DiscordResult permanent(int status, int code, String error) {
        return new DiscordResult(Kind.PERMANENT, status, code, 0, error);
    }

    public boolean isSuccess() {
        return kind == Kind.SUCCESS;
    }
}
