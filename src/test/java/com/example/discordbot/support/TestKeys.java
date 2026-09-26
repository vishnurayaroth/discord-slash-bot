package com.example.discordbot.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;

/** A throwaway Ed25519 key pair standing in for Discord's, so tests can sign real requests. */
public final class TestKeys {

    private final KeyPair pair;

    public TestKeys() {
        try {
            this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The X.509 encoding of an Ed25519 public key ends with the 32 raw key bytes. */
    public String publicKeyHex() {
        byte[] encoded = pair.getPublic().getEncoded();
        return HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    public String sign(String timestamp, String body) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(timestamp.getBytes(StandardCharsets.UTF_8));
            s.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
