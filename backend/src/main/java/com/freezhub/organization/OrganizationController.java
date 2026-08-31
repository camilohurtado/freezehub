package com.freezhub.organization;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.audit.FieldChanges;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The authenticated caller's own organization and its settings (FZ-047).
 *
 * <p>There is no organization id in any path here, and there could not be: the
 * organization comes from the credential, so a caller can only ever address its own.
 *
 * <p>Reading is open to any member — knowing how much warning the team gets before a
 * freeze is not sensitive. Changing it is Administrator-only, like every other setting
 * that decides something on the whole organization's behalf.
 */
@RestController
@RequestMapping("/api/organization")
public class OrganizationController {

    private final OrganizationRepository organizationRepository;
    private final AuditTrail auditTrail;

    public OrganizationController(OrganizationRepository organizationRepository, AuditTrail auditTrail) {
        this.organizationRepository = organizationRepository;
        this.auditTrail = auditTrail;
    }

    @GetMapping
    public OrganizationResponse get(@AuthenticationPrincipal AuthenticatedUser caller) {
        return OrganizationResponse.from(owned(caller));
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public OrganizationResponse updateSettings(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @Valid @RequestBody SettingsRequest request) {
        Organization organization = owned(caller);

        FieldChanges changes = FieldChanges.builder().compare(
                "startingSoonLeadTimeMinutes",
                organization.getStartingSoonLeadTimeMinutes(),
                request.startingSoonLeadTimeMinutes());

        organization.setStartingSoonLeadTimeMinutes(request.startingSoonLeadTimeMinutes());
        Organization saved = organizationRepository.save(organization);

        // Transactional so the change and its record commit together, which is the whole
        // contract of AuditTrail (FZ-060).
        if (!changes.isEmpty()) {
            auditTrail.record(caller.organizationId(), AuditActor.of(caller),
                    AuditAction.ORGANIZATION_SETTINGS_CHANGED, AuditResourceType.ORGANIZATION,
                    organization.getId(), changes.toJson());
        }

        return OrganizationResponse.from(saved);
    }

    private Organization owned(AuthenticatedUser caller) {
        return organizationRepository.findById(caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    }

    /**
     * Bounds are duplicated from {@link Organization} on purpose: enforcing them here
     * turns a bad value into a 400 with a usable message, rather than letting it reach
     * the entity and surface as a 500.
     */
    public record SettingsRequest(
            @Min(Organization.MIN_STARTING_SOON_LEAD_TIME_MINUTES)
            @Max(Organization.MAX_STARTING_SOON_LEAD_TIME_MINUTES)
            int startingSoonLeadTimeMinutes
    ) {
    }

    public record OrganizationResponse(Long id, String name, int startingSoonLeadTimeMinutes) {

        static OrganizationResponse from(Organization organization) {
            return new OrganizationResponse(
                    organization.getId(),
                    organization.getName(),
                    organization.getStartingSoonLeadTimeMinutes());
        }
    }

}
