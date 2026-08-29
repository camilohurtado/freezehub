package com.freezhub.apikey;

import java.time.Instant;

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
                apiKey.getRevokedAt(),
                apiKey.isRevoked());
    }

}
