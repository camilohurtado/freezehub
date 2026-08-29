package com.freezhub.apikey;

import com.freezhub.apikey.ApiKeyService.IssuedApiKey;
import java.time.Instant;

/**
 * The creation response, and the only place the raw {@code key} ever appears.
 *
 * <p>It is a separate type from {@link ApiKeyResponse} on purpose: the secret is returned
 * by exactly one endpoint, and a field that exists only on that endpoint's type cannot be
 * leaked into a listing by accident.
 */
public record IssuedApiKeyResponse(
        Long id,
        String name,
        String keyPrefix,
        String key,
        Long createdBy,
        Instant createdAt
) {

    static IssuedApiKeyResponse from(IssuedApiKey issued) {
        ApiKey apiKey = issued.apiKey();
        return new IssuedApiKeyResponse(
                apiKey.getId(),
                apiKey.getName(),
                apiKey.getKeyPrefix(),
                issued.rawKey(),
                apiKey.getCreatedBy(),
                apiKey.getCreatedAt());
    }

}
