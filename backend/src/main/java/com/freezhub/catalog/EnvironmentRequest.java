package com.freezhub.catalog;

import jakarta.validation.constraints.NotBlank;

public record EnvironmentRequest(@NotBlank String name) {
}
