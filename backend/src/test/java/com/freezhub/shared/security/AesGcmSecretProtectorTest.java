package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Encryption of stored secret material (FZ-049), without a Spring context. */
class AesGcmSecretProtectorTest {

    private static final String KEY = key("development-only-key-not-secret!");
    private static final String OTHER_KEY = key("a-completely-different-32b-key!!");

    private static String key(String literal) {
        return Base64.getEncoder().encodeToString(literal.getBytes(StandardCharsets.UTF_8));
    }

    private final AesGcmSecretProtector protector = new AesGcmSecretProtector(KEY);

    @Test
    void roundTripsASecret() {
        String secret = "{\"webhookUrl\":\"https://hooks.slack.com/services/T0/B0/XXXX\"}";

        assertThat(protector.reveal(protector.protect(secret))).isEqualTo(secret);
    }

    @Test
    void storesSomethingThatDoesNotContainThePlaintext() {
        // The whole point: a database dump must not hand over the credential.
        String secret = "https://hooks.slack.com/services/T0/B0/SUPERSECRET";

        assertThat(protector.protect(secret))
                .doesNotContain("SUPERSECRET")
                .doesNotContain("hooks.slack.com")
                .startsWith("fzenc1:");
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        // A fresh IV per encryption, so two integrations sharing a value do not betray
        // that fact, and repeated writes are not correlatable.
        String secret = "the same value";

        assertThat(protector.protect(secret)).isNotEqualTo(protector.protect(secret));
    }

    @Test
    void refusesToDecryptSomethingThatWasTamperedWith() {
        // GCM authenticates as well as encrypts: an altered row fails loudly rather than
        // yielding different plaintext.
        String stored = protector.protect("https://hooks.slack.com/services/T0/B0/XXXX");
        String tampered = stored.substring(0, stored.length() - 4) + "AAAA";

        assertThatThrownBy(() -> protector.reveal(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not decrypt");
    }

    @Test
    void refusesToDecryptWithTheWrongKey() {
        String stored = protector.protect("a secret");

        assertThatThrownBy(() -> new AesGcmSecretProtector(OTHER_KEY).reveal(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesThroughValuesWrittenBeforeEncryptionExisted() {
        // Rows written before FZ-049 have no scheme prefix. They keep working and are
        // encrypted the next time they are written, so no bulk migration is needed.
        String legacy = "{\"url\":\"https://acme.test/hooks\"}";

        assertThat(protector.reveal(legacy)).isEqualTo(legacy);
    }

    @Test
    void treatsNullAsNothingToProtect() {
        assertThat(protector.protect(null)).isNull();
        assertThat(protector.reveal(null)).isNull();
    }

    @Test
    void refusesAKeyThatIsNotAes256() {
        // Failing at startup beats running with a key that is weaker than intended.
        assertThatThrownBy(() -> new AesGcmSecretProtector(key("too-short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new AesGcmSecretProtector("not base64 at all!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

}
