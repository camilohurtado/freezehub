package com.freezhub.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamApplicationRepository extends JpaRepository<TeamApplication, TeamApplicationId> {

    List<TeamApplication> findAllByApplicationId(Long applicationId);

    boolean existsByTeamIdAndApplicationId(Long teamId, Long applicationId);

    void deleteByTeamIdAndApplicationId(Long teamId, Long applicationId);

}
