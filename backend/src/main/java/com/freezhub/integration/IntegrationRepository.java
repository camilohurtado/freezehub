package com.freezhub.integration;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRepository extends JpaRepository<Integration, Long> {

    /** Destinations a notification should fan out to; disabled ones are skipped. */
    List<Integration> findAllByOrganizationIdAndEnabledTrue(Long organizationId);

    List<Integration> findAllByOrganizationId(Long organizationId);

    Optional<Integration> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * What the plan limit counts (FZ-081). Disabled destinations count: they are still
     * configured, still hold an encrypted credential, and are one toggle from sending.
     */
    long countByOrganizationId(Long organizationId);

}
