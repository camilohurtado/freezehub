package com.freezhub.apikey;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * The authentication lookup. Unique by database constraint, so a presented key
     * resolves to exactly one credential and therefore exactly one organization.
     */
    Optional<ApiKey> findByTokenHash(String tokenHash);

    List<ApiKey> findAllByOrganizationIdOrderByIdAsc(Long organizationId);

    Optional<ApiKey> findByIdAndOrganizationId(Long id, Long organizationId);

}
