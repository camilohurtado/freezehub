package com.freezhub.shared.security;

/**
 * Identity resolved from a machine request's {@code X-API-Key} header (FZ-052).
 *
 * <p>Deliberately not an {@link AuthenticatedUser}: no person is behind a pipeline call,
 * so there is no user id, email or role to invent. `06-security.md` keeps the human and
 * machine mechanisms distinct, and giving them distinct principals means code cannot
 * accidentally treat a CI client as a signed-in administrator.
 *
 * <p>{@code organizationId} comes from the stored credential, never from the request.
 */
public record ApiKeyPrincipal(Long apiKeyId, Long organizationId, String name) {
}
