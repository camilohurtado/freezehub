package com.freezhub.catalog;

import java.time.Instant;

public record EnvironmentResponse(Long id, String name, Instant createdAt, Instant updatedAt) {

    static EnvironmentResponse from(Environment environment) {
        return new EnvironmentResponse(
                environment.getId(), environment.getName(), environment.getCreatedAt(), environment.getUpdatedAt());
    }

}
