package com.example.discordbot.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminAuthTest {

    // Cost 4 is the BCrypt minimum: fast enough for tests, same code path as production.
    private final AdminAuth auth = new AdminAuth("admin", AdminAuth.hash("correct horse", 4));

    @Test
    void correctCredentialsPass() {
        assertTrue(auth.check("admin", "correct horse"));
    }

    @Test
    void aWrongPasswordAndAWrongUsernameBothJustFail() {
        assertFalse(auth.check("admin", "wrong"));
        assertFalse(auth.check("someone", "correct horse"));
        assertFalse(auth.check("someone", "wrong"), "the caller cannot tell which part was wrong");
    }

    @Test
    void missingInputFails() {
        assertFalse(auth.check(null, null));
        assertFalse(auth.check("admin", null));
        assertFalse(auth.check(null, "correct horse"));
        assertFalse(auth.check("", ""));
    }

    @Test
    void theStoredValueIsAHashNotThePassword() {
        String hash = AdminAuth.hash("correct horse", 4);
        assertNotEquals("correct horse", hash);
        assertTrue(hash.startsWith("$2"), "a BCrypt hash");
        assertFalse(hash.contains("correct horse"));
    }

    @Test
    void aMalformedStoredHashNeverLetsAnyoneIn() {
        AdminAuth broken = new AdminAuth("admin", "not-a-bcrypt-hash");
        assertFalse(broken.check("admin", "correct horse"));
        assertFalse(broken.check("admin", ""));
    }
}
