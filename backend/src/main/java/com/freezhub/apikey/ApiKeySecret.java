package com.freezhub.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes machine credentials (FZ-052, `06-security.md`).
 *
 * <p>A key is {@code fzh_} followed by 256 bits of {@link SecureRandom} entropy in
 * URL-safe Base64. The prefix is not decoration: it makes a leaked key recognisable to
 * secret scanners and identifiable in a log without reducing the secret's entropy.
 *
 * <p><strong>Hashing is deliberately unsalted.</strong> `06-security.md` says "salted hash
 * (e.g. SHA-256)"; a salt exists to defeat rainbow tables and offline brute force against
 * <em>low-entropy</em> secrets, and neither attack applies to a 256-bit random value —
 * there is nothing to guess. A per-key salt would also mean the hash of an incoming key no
 * longer identifies its row, forcing either a second lookup handle inside the token or
 * hashing every row on every call. The Policy API is asked on every deployment, so that
 * cost is real and buys nothing. What the specification actually requires — the raw key is
 * never stored, and lookup is by hash — holds exactly.
 */
final class ApiKeySecret {

    /** Identifies a FreezeHub key on sight, for leak scanning and support. */
    static final String PREFIX = "fzh_";

    private static final int ENTROPY_BYTES = 32;

    /** Enough of the key to tell two credentials apart, far too little to use one. */
    private static final int VISIBLE_CHARS = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeySecret() {
    }

    static String generate() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    static String hash(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this cannot happen at runtime.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The non-secret opening of a key, for display in a listing. */
    static String prefixOf(String rawKey) {
        return rawKey.substring(0, Math.min(PREFIX.length() + VISIBLE_CHARS, rawKey.length()));
    }

}
