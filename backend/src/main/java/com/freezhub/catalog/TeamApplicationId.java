package com.freezhub.catalog;

import java.io.Serializable;
import java.util.Objects;

public class TeamApplicationId implements Serializable {

    private Long teamId;
    private Long applicationId;

    protected TeamApplicationId() {
    }

    public TeamApplicationId(Long teamId, Long applicationId) {
        this.teamId = teamId;
        this.applicationId = applicationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TeamApplicationId that)) {
            return false;
        }
        return Objects.equals(teamId, that.teamId) && Objects.equals(applicationId, that.applicationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, applicationId);
    }

}
