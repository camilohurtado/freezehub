package com.freezhub.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secret material with AES-256-GCM before it is stored (FZ-049).
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a row edited
 * directly in the database fails to decrypt instead of quietly yielding different
 * plaintext.
 *
 * <p>Stored form is {@code fzenc1:} followed by Base64 of {@code iv || ciphertext+tag}.
 * The prefix carries a scheme version, which is what makes a future change — a new
 * algorithm, or delegating to a secrets manager — rolloutable rather than a flag day.
 *
 * <p><strong>What this does and does not protect.</strong> Someone with a database
 * connection, a dump, or a backup gets ciphertext. Someone who has compromised the
 * running application has the key too, and this stops them from nothing. That is the
 * accepted limit of encrypting in the application, and the reason the key itself belongs
 * in a secrets manager rather than in a config file.
 */
@Component
public class AesGcmSecretProtector implements SecretProtector {

    /** Scheme marker and version. Anything without it is pre-FZ-049 plaintext. */
    static final String PREFIX = "fzenc1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * @param base64Key 32 bytes, Base64-encoded. Declared without a default so an
     *                  environment that forgets it fails to start rather than running
     *                  with a key everyone shares — the same fail-fast as the missing
     *                  {@code JwtDecoder} outside the local profile.
     */
    public AesGcmSecretProtector(@Value("${freezehub.secrets.encryption-key}") String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "freezehub.secrets.encryption-key must be Base64-encoded", notBase64);
        }

        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("freezehub.secrets.encryption-key must decode to "
                    + KEY_BYTES + " bytes (AES-256), got " + decoded.length);
        }

        this.key = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public String protect(String plaintext) {
        if (plaintext == null) {
            return null;
        }

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException failed) {
            // Never include the plaintext in the message: this lands in logs.
            throw new IllegalStateException("Could not encrypt a stored secret", failed);
        }
    }

    @Override
    public String reveal(String stored) {
        if (stored == null) {
            return null;
        }

        // Written before FZ-049. Returned as-is so existing rows keep working; they are
        // encrypted the next time they are written. See D-3 for why no bulk re-encryption.
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }

        byte[] envelope = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failed) {
            // Wrong key, or the row was altered. Both are worth failing loudly for.
            throw new IllegalStateException("Could not decrypt a stored secret", failed);
        }
    }

}
