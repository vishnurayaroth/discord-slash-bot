package com.example.discordbot.persistence;

/**
 * Asked immediately before commit. Returning false means the request already gave up (the
 * deadline passed), so the store must roll back and the command is never accepted.
 */
@FunctionalInterface
public interface CommitPermit {
    boolean tryBeginCommit();
}
