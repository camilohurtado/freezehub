package com.freezhub.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Where an organization's notifications are sent (FZ-040).
 *
 * <p>{@code config} is opaque here on purpose: each channel needs different settings, and
 * only the channel that owns a type interprets them. Nothing generic parses this.
 */
@Entity
@Table(name = "integration")
public class Integration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationType type;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private String config;

    /**
     * Shared secret a webhook receiver uses to verify a delivery came from FreezeHub
     * (FZ-048). Null for every other channel, and for webhooks created before that
     * story until they are rotated.
     *
     * <p>Held recoverable rather than hashed, because signing needs the key itself.
     */
    @Column(name = "signing_secret")
    private String signingSecret;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Integration() {
    }

    public Integration(Long organizationId, IntegrationType type, String config) {
        this.organizationId = organizationId;
        this.type = type;
        this.config = config;
        this.enabled = true;
        // Enforced here rather than in the service so a webhook cannot be created
        // without one by any code path, present or future (FZ-048).
        if (type == IntegrationType.WEBHOOK) {
            this.signingSecret = WebhookSigning.generateSecret();
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public IntegrationType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    /**
     * Issues a new signing secret, invalidating the previous one immediately.
     *
     * <p>Deliberately abrupt: there is no overlap window in which both secrets are
     * accepted, so a rotation is a coordinated change with the receiver. Supporting two
     * live secrets would mean a leaked one keeps working for the length of the window,
     * which is the opposite of why anyone rotates.
     */
    void rotateSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public String getConfig() {
        return config;
    }

    /** Replaces the channel settings. Validated by IntegrationConfigs before it gets here. */
    public void setConfig(String config) {
        this.config = config;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
