package com.freezhub.integration;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRepository extends JpaRepository<Integration, Long> {

    /** Destinations a notification should fan out to; disabled ones are skipped. */
    List<Integration> findAllByOrganizationIdAndEnabledTrue(Long organizationId);

    List<Integration> findAllByOrganizationId(Long organizationId);

    Optional<Integration> findByIdAndOrganizationId(Long id, Long organizationId);

}
