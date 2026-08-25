package com.freezhub.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findAllByOrganizationId(Long organizationId);

    Optional<Application> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndName(Long organizationId, String name);

    /** Batched ownership check: compare against the requested id count. */
    long countByOrganizationIdAndIdIn(Long organizationId, Collection<Long> ids);

}
