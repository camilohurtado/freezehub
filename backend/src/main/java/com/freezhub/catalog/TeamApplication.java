package com.freezhub.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "team_application")
@IdClass(TeamApplicationId.class)
public class TeamApplication {

    @Id
    @Column(name = "team_id")
    private Long teamId;

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    protected TeamApplication() {
    }

    public TeamApplication(Long teamId, Long applicationId) {
        this.teamId = teamId;
        this.applicationId = applicationId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public Long getApplicationId() {
        return applicationId;
    }

}
