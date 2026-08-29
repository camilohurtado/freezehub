package com.freezhub.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Key generation and hashing, without a Spring context (FZ-052). */
class ApiKeySecretTest {

    @Test
    void generatesAPrefixedHighEntropyKey() {
        String key = ApiKeySecret.generate();

        assertThat(key).startsWith("fzh_");
        // 32 random bytes as unpadded URL-safe Base64 is 43 characters.
        assertThat(key.substring("fzh_".length())).hasSize(43);
    }

    @Test
    void generatesADistinctKeyEveryTime() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            keys.add(ApiKeySecret.generate());
        }

        assertThat(keys).hasSize(1_000);
    }

    @Test
    void producesUrlSafeKeysSoAHeaderNeverNeedsEncoding() {
        // A key travels in an HTTP header and gets pasted into CI configuration; anything
        // outside this alphabet would eventually be mangled by something.
        assertThat(ApiKeySecret.generate()).matches("fzh_[A-Za-z0-9_-]+");
    }

    @Test
    void hashesTheSameKeyToTheSameValue() {
        // Lookup depends on this: the hash of a presented key must equal the stored one.
        String key = ApiKeySecret.generate();

        assertThat(ApiKeySecret.hash(key)).isEqualTo(ApiKeySecret.hash(key));
    }

    @Test
    void hashesDifferentKeysDifferently() {
        assertThat(ApiKeySecret.hash(ApiKeySecret.generate()))
                .isNotEqualTo(ApiKeySecret.hash(ApiKeySecret.generate()));
    }

    @Test
    void producesAHashThatDoesNotContainTheKey() {
        String key = ApiKeySecret.generate();
        String hash = ApiKeySecret.hash(key);

        assertThat(hash).doesNotContain(key.substring("fzh_".length()));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void exposesOnlyAShortOpeningOfTheKeyForDisplay() {
        String key = ApiKeySecret.generate();

        String prefix = ApiKeySecret.prefixOf(key);

        assertThat(key).startsWith(prefix);
        // Short enough that what is shown cannot be worked back into the key: 6 of 43
        // characters leaves the overwhelming majority of the entropy unseen.
        assertThat(prefix).hasSize("fzh_".length() + 6);
    }

}
