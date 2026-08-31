package com.freezhub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The webhook signing scheme (FZ-048), independent of any delivery. */
class WebhookSigningTest {

    @Test
    void producesTheSignatureAReceiverWouldComputeIndependently() {
        // A pinned vector, computed outside this codebase with:
        //   printf '1700000000.{"event":"CANCELLED"}' \
        //     | openssl dgst -sha256 -hmac 'whsec_testsecret' -hex
        //
        // The point is the *signed string format*, not the algorithm: change the "." to
        // anything else, or sign the body without the timestamp, and every receiver in
        // the world silently starts rejecting deliveries. This is what pins it.
        String signature = WebhookSigning.sign("whsec_testsecret", 1700000000L, "{\"event\":\"CANCELLED\"}");

        assertThat(signature)
                .isEqualTo("sha256=1b0bb697b274865841df445937379a321f05f302db0ea4c3bff6ef9bb739c2ad");
    }

    @Test
    void namesItsAlgorithmSoTheSchemeCanChangeLater() {
        assertThat(WebhookSigning.sign("whsec_x", 1L, "{}")).startsWith("sha256=");
    }

    @Test
    void bindsTheSignatureToTheBody() {
        // Tampering must invalidate it, or the signature proves only origin, not content.
        String original = WebhookSigning.sign("whsec_x", 1700000000L, "{\"event\":\"ACTIVATED\"}");
        String tampered = WebhookSigning.sign("whsec_x", 1700000000L, "{\"event\":\"CANCELLED\"}");

        assertThat(original).isNotEqualTo(tampered);
    }

    @Test
    void bindsTheSignatureToTheTimestamp() {
        // Without this a captured delivery could be replayed for ever with its own
        // signature intact; with it, a receiver rejecting old timestamps is protected.
        String now = WebhookSigning.sign("whsec_x", 1700000000L, "{}");
        String later = WebhookSigning.sign("whsec_x", 1700000060L, "{}");

        assertThat(now).isNotEqualTo(later);
    }

    @Test
    void bindsTheSignatureToTheSecret() {
        assertThat(WebhookSigning.sign("whsec_one", 1L, "{}"))
                .isNotEqualTo(WebhookSigning.sign("whsec_two", 1L, "{}"));
    }

    @Test
    void generatesAPrefixedHighEntropySecret() {
        String secret = WebhookSigning.generateSecret();

        assertThat(secret).startsWith("whsec_").matches("whsec_[A-Za-z0-9_-]+");
        // 32 random bytes as unpadded URL-safe Base64.
        assertThat(secret.substring("whsec_".length())).hasSize(43);
    }

    @Test
    void generatesADistinctSecretEveryTime() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            secrets.add(WebhookSigning.generateSecret());
        }

        assertThat(secrets).hasSize(1_000);
    }

}
