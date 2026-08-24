package com.freezhub.shared.security;

import com.freezhub.organization.UserRole;

/**
 * Identity resolved from an authenticated request, per 06-security.md: organization_id
 * always comes from this server-side resolution, never from client input.
 */
public record AuthenticatedUser(Long userId, Long organizationId, String email, UserRole role) {
}
