package com.freezhub.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Creation payload. {@code config} is raw JSON whose shape depends on {@code type};
 * IntegrationConfigs is what decides whether it is usable.
 */
public record IntegrationRequest(@NotNull IntegrationType type, @NotBlank String config) {
}
