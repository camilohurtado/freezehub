package com.freezhub.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

    List<Environment> findAllByOrganizationId(Long organizationId);

    Optional<Environment> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndName(Long organizationId, String name);

    /** Policy evaluation identifies catalog entries by name, not id (`04-api.md`). */
    Optional<Environment> findByOrganizationIdAndName(Long organizationId, String name);

    /** Batched ownership check: compare against the requested id count. */
    long countByOrganizationIdAndIdIn(Long organizationId, Collection<Long> ids);

}
