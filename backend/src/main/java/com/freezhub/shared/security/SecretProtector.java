package com.freezhub.shared.security;

/**
 * Protects secret material before it reaches the database, and reveals it on the way back
 * (FZ-049, decision {@code D-3}).
 *
 * <p>A port rather than a static utility precisely so the mechanism can change without
 * touching anything that stores a secret. Today there is one implementation,
 * {@link AesGcmSecretProtector}, which encrypts in the application with a key supplied by
 * configuration. A deployed environment sources that key from AWS Secrets Manager.
 *
 * <p>The other lane this deliberately leaves open is delegating outright — storing the
 * secret <em>in</em> Secrets Manager (or another provider's equivalent) and keeping only a
 * reference in the row. That is a different implementation of this interface, not a
 * rewrite of its callers.
 */
public interface SecretProtector {

    /** Returns a form safe to persist. Null in, null out. */
    String protect(String plaintext);

    /** Reverses {@link #protect}. Null in, null out. */
    String reveal(String stored);

}
