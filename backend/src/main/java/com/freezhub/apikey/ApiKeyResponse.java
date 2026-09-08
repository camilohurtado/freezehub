package com.freezhub.apikey;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What a client is allowed to see about an existing key.
 *
 * <p>Note what is absent: the raw key (never stored) and its hash (not useful to a client,
 * and handing out the stored form of a credential is a habit worth not forming).
 * {@code keyPrefix} identifies which credential a row is without being enough to use it.
 */
public record ApiKeyResponse(
        Long id,
        String name,
        String keyPrefix,
        Long createdBy,
        Instant createdAt,
        /**
         * The day this key last authenticated something, or null if it never has (FZ-117).
         *
         * <p>A date, not an instant: deciding which of four keys is safe to revoke needs
         * to know whether anything still uses it, and a day answers that. Precision to the
         * second would cost a write on every deployment check.
         */
        LocalDate lastUsedOn,
        Instant revokedAt,
        boolean revoked
) {

    static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getName(),
                apiKey.getKeyPrefix(),
                apiKey.getCreatedBy(),
                apiKey.getCreatedAt(),
                apiKey.getLastUsedOn(),
                apiKey.getRevokedAt(),
                apiKey.isRevoked());
    }

}
