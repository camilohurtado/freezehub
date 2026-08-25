package com.freezhub.catalog;

import java.time.Instant;
import java.util.List;

public record ApplicationResponse(Long id, String name, List<Long> teamIds, Instant createdAt, Instant updatedAt) {

    static ApplicationResponse from(Application application, List<Long> teamIds) {
        return new ApplicationResponse(
                application.getId(), application.getName(), teamIds, application.getCreatedAt(), application.getUpdatedAt());
    }

}
