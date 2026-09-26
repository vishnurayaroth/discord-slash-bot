package com.example.discordbot.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final long NOW = 1_700_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final byte[] BODY = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);

    private static KeyPair pair;
    private static KeyPair other;
    private static SignatureVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        pair = gen.generateKeyPair();
        other = gen.generateKeyPair();
        verifier = new SignatureVerifier(rawKeyHex(pair), CLOCK);
    }

    /** The X.509 encoding of an Ed25519 public key ends with the 32 raw key bytes. */
    static String rawKeyHex(KeyPair p) {
        byte[] encoded = p.getPublic().getEncoded();
        return HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    static String sign(KeyPair p, String timestamp, byte[] body) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(p.getPrivate());
        s.update(timestamp.getBytes(StandardCharsets.UTF_8));
        s.update(body);
        return HexFormat.of().formatHex(s.sign());
    }

    private static String ts(long secondsFromNow) {
        return Long.toString(NOW + secondsFromNow);
    }

    @Test
    void validSignatureAndFreshTimestampPasses() throws Exception {
        String t = ts(0);
        assertTrue(verifier.isValid(sign(pair, t, BODY), t, BODY));
    }

    @Test
    void tamperedBodyFails() throws Exception {
        String t = ts(0);
        String sig = sign(pair, t, BODY);
        assertFalse(verifier.isValid(sig, t, "{\"type\":2}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureFromAnotherKeyFails() throws Exception {
        String t = ts(0);
        assertFalse(verifier.isValid(sign(other, t, BODY), t, BODY));
    }

    @Test
    void malformedOrMissingInputsFail() throws Exception {
        String t = ts(0);
        String good = sign(pair, t, BODY);
        assertFalse(verifier.isValid("not-hex", t, BODY));
        assertFalse(verifier.isValid(good.substring(2), t, BODY), "short signature");
        assertFalse(verifier.isValid(null, t, BODY));
        assertFalse(verifier.isValid("", t, BODY));
        assertFalse(verifier.isValid(good, null, BODY));
        assertFalse(verifier.isValid(good, "abc", BODY), "non-numeric timestamp");
        assertFalse(verifier.isValid(good, t, null));
    }

    @Test
    void staleTimestampFailsEvenWithAGenuineSignature() throws Exception {
        String old = ts(-16);
        assertFalse(verifier.isValid(sign(pair, old, BODY), old, BODY));
        String edge = ts(-15);
        assertTrue(verifier.isValid(sign(pair, edge, BODY), edge, BODY), "15 s old is still accepted");
    }

    @Test
    void farFutureTimestampFails() throws Exception {
        String future = ts(6);
        assertFalse(verifier.isValid(sign(pair, future, BODY), future, BODY));
        String edge = ts(5);
        assertTrue(verifier.isValid(sign(pair, edge, BODY), edge, BODY), "5 s ahead is tolerated (clock drift)");
    }

    /** SC-002: a batch of bad requests is rejected in full. */
    @Test
    void twentyMixedBadRequestsAreAllRejected() throws Exception {
        String t = ts(0);
        String good = sign(pair, t, BODY);
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            byte[] tampered = ("{\"type\":" + (10 + i) + "}").getBytes(StandardCharsets.UTF_8);
            if (!verifier.isValid(good, t, tampered)) rejected++;            // tampered body
            if (!verifier.isValid(sign(other, t, BODY), t, BODY)) rejected++; // wrong key
            String old = ts(-60 - i);
            if (!verifier.isValid(sign(pair, old, BODY), old, BODY)) rejected++; // stale replay
            if (!verifier.isValid("00".repeat(64), t, BODY)) rejected++;      // forged signature
        }
        org.junit.jupiter.api.Assertions.assertEquals(20, rejected);
    }
}
