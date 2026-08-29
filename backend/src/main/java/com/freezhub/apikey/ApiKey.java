package com.freezhub.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An organization-owned machine credential used by CI/CD clients (FZ-052).
 *
 * <p>The raw key is not a field here and never becomes one: only its SHA-256 hash is
 * persisted, so the secret exists in one HTTP response and nowhere else afterwards
 * (`01-domain.md`, `06-security.md`).
 *
 * <p>There is no {@code updated_at}: everything about a key is fixed at creation except
 * revocation, which happens once.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    ApiKey(Long organizationId, String name, String keyPrefix, String tokenHash, Long createdBy) {
        this.organizationId = organizationId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Withdraws the credential permanently.
     *
     * <p>Not reversible, and not a toggle like {@code Integration.enabled}: a key is
     * revoked because it may already be in someone else's hands, and restoring it would
     * bring that copy back to life. Issue a new key instead.
     */
    void revoke() {
        this.revokedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

}
