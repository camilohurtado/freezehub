package com.freezhub.integration;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The webhook signing scheme (FZ-048), in one place so sender and receiver
 * documentation cannot drift apart.
 *
 * <p>A receiver recomputes the HMAC over {@code timestamp + "." + body} with its shared
 * secret and compares. Signing the timestamp <em>with</em> the body is what makes the
 * signature non-replayable: a captured request cannot be re-sent with a different body,
 * and a receiver that rejects old timestamps cannot be fed a stale one for ever.
 *
 * <p><strong>Not reusing {@code ApiKeySecret}, deliberately.</strong> The generation looks
 * identical, but the storage lifecycle is the opposite: an API key is stored as a hash and
 * verified by hashing what arrives, whereas this secret must be kept recoverable because
 * signing needs the key itself. Sharing a type would suggest the two are interchangeable
 * and invite someone to store this one hashed, which would silently break every delivery.
 */
public final class WebhookSigning {

    /** Named in the header value so the algorithm can change without ambiguity. */
    public static final String ALGORITHM_PREFIX = "sha256=";

    public static final String SIGNATURE_HEADER = "X-FreezeHub-Signature";
    public static final String TIMESTAMP_HEADER = "X-FreezeHub-Timestamp";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SECRET_PREFIX = "whsec_";
    private static final int ENTROPY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSigning() {
    }

    /** A new shared secret: {@code whsec_} plus 256 bits of entropy, URL-safe. */
    public static String generateSecret() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** The value of {@code X-FreezeHub-Signature} for one delivery. */
    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(
                    (timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return ALGORITHM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException impossible) {
            // HmacSHA256 is required of every JVM, and the key is never empty here.
            throw new IllegalStateException("Could not sign the webhook payload", impossible);
        }
    }

}
