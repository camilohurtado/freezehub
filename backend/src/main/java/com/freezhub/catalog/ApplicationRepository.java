package com.freezhub.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findAllByOrganizationId(Long organizationId);

    Optional<Application> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndName(Long organizationId, String name);

}
