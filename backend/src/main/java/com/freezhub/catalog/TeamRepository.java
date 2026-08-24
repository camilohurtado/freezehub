package com.freezhub.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrganizationId(Long organizationId);

    Optional<Team> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndName(Long organizationId, String name);

}
