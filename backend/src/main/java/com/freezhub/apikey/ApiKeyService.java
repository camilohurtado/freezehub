package com.freezhub.apikey;

import com.freezhub.shared.security.ApiKeyPrincipal;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    public List<ApiKey> list(Long organizationId) {
        return apiKeyRepository.findAllByOrganizationIdOrderByIdAsc(organizationId);
    }

    /**
     * Issues a credential and returns it together with the raw key.
     *
     * <p>The raw key is returned to the caller and then forgotten: it is not stored, not
     * logged, and cannot be read back. Losing it means issuing a new one.
     */
    @Transactional
    public IssuedApiKey create(Long organizationId, Long createdBy, String name) {
        String rawKey = ApiKeySecret.generate();

        ApiKey apiKey = apiKeyRepository.save(new ApiKey(
                organizationId, name, ApiKeySecret.prefixOf(rawKey), ApiKeySecret.hash(rawKey), createdBy));

        return new IssuedApiKey(apiKey, rawKey);
    }

    /**
     * Withdraws a credential. Repeating it is a 409 rather than a silent success, matching
     * cancellation of a restriction: a second revoke means the caller believed the key was
     * still live, which is worth telling them.
     */
    @Transactional
    public ApiKey revoke(Long organizationId, Long apiKeyId) {
        ApiKey apiKey = findOwned(organizationId, apiKeyId);

        if (apiKey.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This API key is already revoked");
        }

        apiKey.revoke();
        return apiKey;
    }

    /**
     * Resolves a presented key to the organization that owns it, or nothing.
     *
     * <p>Every failure is the same empty result — unknown key, revoked key, malformed
     * input — so the caller cannot learn from a rejection whether a key ever existed.
     */
    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        return apiKeyRepository.findByTokenHash(ApiKeySecret.hash(rawKey))
                .filter(apiKey -> !apiKey.isRevoked())
                .map(apiKey -> new ApiKeyPrincipal(
                        apiKey.getId(), apiKey.getOrganizationId(), apiKey.getName()));
    }

    /** Another organization's key is indistinguishable from one that never existed. */
    private ApiKey findOwned(Long organizationId, Long apiKeyId) {
        return apiKeyRepository.findByIdAndOrganizationId(apiKeyId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
    }

    /** A newly issued key plus the one and only sight of its raw secret. */
    public record IssuedApiKey(ApiKey apiKey, String rawKey) {
    }

}
