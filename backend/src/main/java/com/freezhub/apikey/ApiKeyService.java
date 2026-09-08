package com.freezhub.apikey;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.subscription.SubscriptionService;
import com.freezhub.shared.security.ApiKeyPrincipal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final AuditTrail auditTrail;
    private final SubscriptionService subscriptions;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, AuditTrail auditTrail,
                         SubscriptionService subscriptions) {
        this.apiKeyRepository = apiKeyRepository;
        this.auditTrail = auditTrail;
        this.subscriptions = subscriptions;
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
    public IssuedApiKey create(Long organizationId, AuditActor actor, String name) {
        // Revoked keys do not count: the limit is on live credentials, and a plan that
        // punished a customer for rotating one would discourage exactly the right habit.
        subscriptions.requireApiKeyHeadroom(organizationId,
                () -> apiKeyRepository.countByOrganizationIdAndRevokedAtIsNull(organizationId));

        String rawKey = ApiKeySecret.generate();

        ApiKey apiKey = apiKeyRepository.save(new ApiKey(
                organizationId, name, ApiKeySecret.prefixOf(rawKey), ApiKeySecret.hash(rawKey), actor.id()));

        // Who granted machine access, and when. The key itself is never recorded — only
        // its non-secret prefix, which is what identifies it in the trail (FZ-060).
        auditTrail.record(organizationId, actor, AuditAction.API_KEY_ISSUED,
                AuditResourceType.API_KEY, apiKey.getId(),
                AuditDetails.builder()
                        .with("name", name)
                        .with("keyPrefix", apiKey.getKeyPrefix())
                        .toJson());

        return new IssuedApiKey(apiKey, rawKey);
    }

    /**
     * Withdraws a credential. Repeating it is a 409 rather than a silent success, matching
     * cancellation of a restriction: a second revoke means the caller believed the key was
     * still live, which is worth telling them.
     */
    @Transactional
    public ApiKey revoke(Long organizationId, AuditActor actor, Long apiKeyId) {
        ApiKey apiKey = findOwned(organizationId, apiKeyId);

        if (apiKey.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This API key is already revoked");
        }

        apiKey.revoke();
        auditTrail.record(organizationId, actor, AuditAction.API_KEY_REVOKED,
                AuditResourceType.API_KEY, apiKey.getId());

        return apiKey;
    }

    /**
     * Resolves a presented key to the organization that owns it, or nothing.
     *
     * <p>Every failure is the same empty result — unknown key, revoked key, malformed
     * input — so the caller cannot learn from a rejection whether a key ever existed.
     */
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        return apiKeyRepository.findByTokenHash(ApiKeySecret.hash(rawKey))
                .filter(apiKey -> !apiKey.isRevoked())
                .map(apiKey -> {
                    /*
                     * Stamped here because this is the only place every machine request
                     * passes through, and skipped unless the day has changed (FZ-117).
                     * A pipeline checking a thousand times today writes once — which is
                     * what keeps the deployment gate a read path in all but name.
                     */
                    if (apiKey.recordUsedOn(LocalDate.now(ZoneOffset.UTC))) {
                        apiKeyRepository.save(apiKey);
                    }
                    return new ApiKeyPrincipal(
                            apiKey.getId(), apiKey.getOrganizationId(), apiKey.getName());
                });
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
