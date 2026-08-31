package com.freezhub.integration;

import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an organization's freeze announcements go (FZ-045).
 *
 * <p>Administrator-only, like invite (`06-security.md`): a destination decides who hears
 * about a freeze, and its configuration can hold a credential.
 */
@RestController
@RequestMapping("/api/integrations")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class IntegrationController {

    private final IntegrationService integrationService;

    public IntegrationController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @GetMapping
    public List<IntegrationResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return integrationService.list(caller.organizationId()).stream()
                .map(IntegrationResponse::from)
                .toList();
    }

    /**
     * Creates a destination. For a WEBHOOK the response carries the {@code signingSecret},
     * which is shown here and nowhere else (FZ-048).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedIntegrationResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @Valid @RequestBody IntegrationRequest request) {
        return IssuedIntegrationResponse.from(
                integrationService.create(caller.organizationId(), request.type(), request.config()));
    }

    /** Issues a new webhook signing secret and returns it once. 409 for any other type. */
    @PostMapping("/{integrationId}/signing-secret")
    public IssuedIntegrationResponse rotateSigningSecret(@AuthenticationPrincipal AuthenticatedUser caller,
                                                         @PathVariable Long integrationId) {
        return IssuedIntegrationResponse.from(
                integrationService.rotateSigningSecret(caller.organizationId(), integrationId));
    }

    @PatchMapping("/{integrationId}")
    public IntegrationResponse setEnabled(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @PathVariable Long integrationId,
                                          @Valid @RequestBody EnabledRequest request) {
        return IntegrationResponse.from(
                integrationService.setEnabled(caller.organizationId(), integrationId, request.enabled()));
    }

    /** Replaces the channel settings — the only way to change a credential, since it is never read back. */
    @PatchMapping("/{integrationId}/config")
    public IntegrationResponse replaceConfig(@AuthenticationPrincipal AuthenticatedUser caller,
                                             @PathVariable Long integrationId,
                                             @Valid @RequestBody ConfigRequest request) {
        return IntegrationResponse.from(
                integrationService.replaceConfig(caller.organizationId(), integrationId, request.config()));
    }

    @DeleteMapping("/{integrationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller,
                       @PathVariable Long integrationId) {
        integrationService.delete(caller.organizationId(), integrationId);
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record ConfigRequest(@NotBlank String config) {
    }

}
