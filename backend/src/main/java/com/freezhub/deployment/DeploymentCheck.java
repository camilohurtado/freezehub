package com.freezhub.deployment;

import com.freezhub.policy.PolicyDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A record that somebody asked whether they could deploy, and what they were told
 * (FZ-070).
 *
 * <p><strong>A check, not a deployment.</strong> FreezeHub observes the question and
 * nothing after it: a pipeline told {@code ALLOW} may still fail for its own reasons, and
 * one told {@code BLOCK} may deploy anyway. Treating these rows as deployments would be
 * wrong in exactly the audit they exist to serve.
 *
 * <p>Immutable, like {@code AuditEvent}: no setters, and nothing updates a row.
 */
@Entity
@Table(name = "deployment_check")
public class DeploymentCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(name = "api_key_label", nullable = false)
    private String apiKeyLabel;

    /** As supplied, not as resolved — an unrecognised name is the interesting case. */
    @Column(nullable = false)
    private String application;

    @Column(nullable = false)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "blocked_reason")
    private BlockedReason blockedReason;

    /** JSON, denormalised: what was true at check time, immune to a later rename. */
    @Column(name = "matched_restrictions")
    private String matchedRestrictions;

    /** Supplied by the caller. Customer PII — never logged. */
    @Column
    private String actor;

    @Column
    private String reference;

    @Column
    private String source;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected DeploymentCheck() {
    }

    DeploymentCheck(Long organizationId, Long apiKeyId, String apiKeyLabel, String application,
                    String environment, PolicyDecision decision, BlockedReason blockedReason,
                    String matchedRestrictions, String actor, String reference, String source) {
        this.organizationId = organizationId;
        this.apiKeyId = apiKeyId;
        this.apiKeyLabel = apiKeyLabel;
        this.application = application;
        this.environment = environment;
        this.decision = decision;
        this.blockedReason = blockedReason;
        this.matchedRestrictions = matchedRestrictions;
        this.actor = actor;
        this.reference = reference;
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        this.checkedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public String getApiKeyLabel() {
        return apiKeyLabel;
    }

    public String getApplication() {
        return application;
    }

    public String getEnvironment() {
        return environment;
    }

    public PolicyDecision getDecision() {
        return decision;
    }

    public BlockedReason getBlockedReason() {
        return blockedReason;
    }

    public String getMatchedRestrictions() {
        return matchedRestrictions;
    }

    public String getActor() {
        return actor;
    }

    public String getReference() {
        return reference;
    }

    public String getSource() {
        return source;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

}
