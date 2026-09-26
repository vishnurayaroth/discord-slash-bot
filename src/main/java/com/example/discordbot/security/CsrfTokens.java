package com.example.discordbot.security;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One anti-forgery token per session, compared in constant time (FR-024). Every state-changing
 * form carries it in a hidden field named {@code csrf}.
 */
public final class CsrfTokens {

    public static final String FIELD = "csrf";
    private static final String ATTRIBUTE = "csrfToken";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfTokens() {}

    /** The session's token, created on first use. */
    public static String tokenFor(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(ATTRIBUTE);
            if (existing instanceof String token) {
                return token;
            }
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(ATTRIBUTE, token);
            return token;
        }
    }

    public static boolean valid(HttpSession session, String provided) {
        Object expected = session.getAttribute(ATTRIBUTE);
        if (!(expected instanceof String token) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
