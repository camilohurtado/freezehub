package com.freezhub.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What a pipeline is about to do (`04-api.md`).
 *
 * <p>The application and environment are named, not identified by id: a pipeline knows
 * {@code payments-api} and {@code production}, and making it discover FreezeHub's internal
 * ids would contradict the product's "simple integration" goal.
 *
 * <p>There is no organization field, and there could not be one — the organization comes
 * from the API key.
 */
public record PolicyEvaluationRequest(
        @NotNull PolicyAction action,
        @NotBlank String application,
        @NotBlank String environment
) {
}
