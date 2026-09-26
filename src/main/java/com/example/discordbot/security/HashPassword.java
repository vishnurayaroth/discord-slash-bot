package com.example.discordbot.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Prints a BCrypt hash for ADMIN_PASSWORD_HASH. The password is read from standard input, never
 * from an argument, so it does not end up in shell history or the process list.
 *
 * <p>Run it from the built WAR, for example:
 * {@code java -cp "target/ROOT/WEB-INF/lib/*:target/ROOT/WEB-INF/classes" com.example.discordbot.security.HashPassword}
 * (unpack the WAR or use the exploded directory Maven creates), then type the password and press Enter.
 */
public final class HashPassword {

    private HashPassword() {}

    public static void main(String[] args) throws IOException {
        System.err.print("Password to hash: ");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String password = in.readLine();
        if (password == null || password.isEmpty()) {
            System.err.println("No password given.");
            System.exit(1);
        }
        System.out.println(AdminAuth.hash(password, AdminAuth.COST));
    }
}
