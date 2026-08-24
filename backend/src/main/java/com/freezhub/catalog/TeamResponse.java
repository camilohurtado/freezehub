package com.freezhub.catalog;

import java.time.Instant;

public record TeamResponse(Long id, String name, Instant createdAt, Instant updatedAt) {

    static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName(), team.getCreatedAt(), team.getUpdatedAt());
    }

}
