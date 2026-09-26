package com.example.discordbot.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.example.discordbot.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the single admin's credentials. The password is only ever compared as a BCrypt hash
 * (FR-019). A wrong username and a wrong password are indistinguishable to the caller: both parts
 * are always evaluated, so neither the result nor the timing says which one was wrong.
 */
public final class AdminAuth {

    /** Cost 10: about 100 ms on a normal CPU; tune down if a login is slow on the free host. */
    public static final int COST = 10;

    private final byte[] username;
    private final String bcryptHash;

    public AdminAuth(AppConfig config) {
        this(config.adminUsername(), config.adminPasswordHash());
    }

    public AdminAuth(String username, String bcryptHash) {
        this.username = username.getBytes(StandardCharsets.UTF_8);
        this.bcryptHash = bcryptHash;
    }

    public boolean check(String givenUsername, String givenPassword) {
        byte[] given = (givenUsername == null ? "" : givenUsername).getBytes(StandardCharsets.UTF_8);
        boolean userOk = MessageDigest.isEqual(username, given);
        char[] password = givenPassword == null ? new char[0] : givenPassword.toCharArray();
        boolean passwordOk = BCrypt.verifyer().verify(password, bcryptHash).verified;
        return userOk & passwordOk; // non-short-circuit on purpose
    }

    /** Produces a hash for ADMIN_PASSWORD_HASH (see HashPassword). */
    public static String hash(String password, int cost) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }
}
