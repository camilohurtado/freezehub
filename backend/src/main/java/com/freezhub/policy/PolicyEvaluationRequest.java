package com.freezhub.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a pipeline is about to do (`04-api.md`).
 *
 * <p>The application and environment are named, not identified by id: a pipeline knows
 * {@code payments-api} and {@code production}, and making it discover FreezeHub's internal
 * ids would contradict the product's "simple integration" goal.
 *
 * <p>There is no organization field, and there could not be one — the organization comes
 * from the API key.
 *
 * <p>{@code actor}, {@code reference} and {@code source} are what turn a decision into a
 * record worth reading (FZ-070): who is deploying, what they are deploying, and where the
 * run can be found. FreezeHub cannot discover any of them — the API key says which
 * pipeline asked, never who pushed the button — so they are supplied or absent.
 *
 * <p><strong>All three are optional</strong>, because not every runner exposes them and a
 * pipeline written before they existed must keep working untouched. They are also customer
 * PII, which is why they are bounded here and never logged.
 */
public record PolicyEvaluationRequest(
        @NotNull PolicyAction action,
        @NotBlank String application,
        @NotBlank String environment,
        /** Who is deploying — an email or username from the CI system. */
        @Size(max = 320) String actor,
        /** What is being deployed — usually a commit SHA or a tag. */
        @Size(max = 255) String reference,
        /** Where to find the run that asked — a pipeline or job URL. */
        @Size(max = 1024) String source
) {
}
