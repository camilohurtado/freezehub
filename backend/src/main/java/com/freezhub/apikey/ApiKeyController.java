package com.freezhub.apikey;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management of an organization's machine credentials (FZ-052).
 *
 * <p>Administrator-only, on the same reasoning as integrations and invites: a key
 * authenticates as the whole organization, so issuing one is a decision about who may act
 * on the organization's behalf.
 *
 * <p>These endpoints are part of the <em>human</em> API and are authenticated with a JWT.
 * A key cannot be used to mint another key — machine authentication reaches only the
 * machine-facing chain in {@code ApiKeySecurityConfig}.
 */
@RestController
@RequestMapping("/api/api-keys")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return apiKeyService.list(caller.organizationId()).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    /** The response carries the raw key. It is not recoverable afterwards. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKeyResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @Valid @RequestBody ApiKeyRequest request) {
        return IssuedApiKeyResponse.from(
                apiKeyService.create(caller.organizationId(), AuditActor.of(caller), request.name()));
    }

    /** 409 if already revoked, 404 if unknown or owned by another organization. */
    @PostMapping("/{apiKeyId}/revoke")
    public ApiKeyResponse revoke(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable Long apiKeyId) {
        return ApiKeyResponse.from(apiKeyService.revoke(caller.organizationId(), AuditActor.of(caller), apiKeyId));
    }

}
