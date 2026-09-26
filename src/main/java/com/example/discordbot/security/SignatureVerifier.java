package com.example.discordbot.security;

import com.example.discordbot.config.Timing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Verifies Discord's Ed25519 request signature over {@code timestamp + raw body} using only the
 * JDK (constitution Principle I). Also rejects stale or far-future timestamps (research.md R6).
 */
public final class SignatureVerifier {

    /** ASN.1 header that wraps a raw 32-byte Ed25519 public key into X.509 form. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final PublicKey publicKey;
    private final Clock clock;

    public SignatureVerifier(String publicKeyHex, Clock clock) {
        byte[] raw = HexFormat.of().parseHex(publicKeyHex.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("the Discord public key must be 32 bytes of hex");
        }
        byte[] encoded = new byte[X509_PREFIX.length + raw.length];
        System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, X509_PREFIX.length, raw.length);
        try {
            this.publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the Discord public key is not a valid Ed25519 key");
        }
        this.clock = clock;
    }

    /** True only for a fresh timestamp and a signature that verifies. Never throws. */
    public boolean isValid(String signatureHex, String timestamp, byte[] body) {
        if (signatureHex == null || timestamp == null || body == null) {
            return false;
        }
        if (!isFresh(timestamp)) {
            return false;
        }
        byte[] signature;
        try {
            signature = HexFormat.of().parseHex(signatureHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (signature.length != 64) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    boolean isFresh(String timestamp) {
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long age = clock.instant().getEpochSecond() - sent;
        return age <= Timing.FRESH_PAST.toSeconds() && age >= -Timing.FRESH_FUTURE.toSeconds();
    }
}
