package com.freezhub.audit;

import com.freezhub.shared.security.ApiKeyPrincipal;
import com.freezhub.shared.security.AuthenticatedUser;

/**
 * Who did it (FZ-060).
 *
 * <p>{@code label} is captured at the time and stored alongside the id rather than being
 * looked up when the trail is read. An audit entry that says "user 42" and needs a join
 * to mean anything stops meaning anything the moment user 42 is removed — and there is no
 * foreign key for the same reason.
 */
public record AuditActor(AuditActorType type, Long id, String label) {

    public static AuditActor of(AuthenticatedUser user) {
        return new AuditActor(AuditActorType.USER, user.userId(), user.email());
    }

    public static AuditActor of(ApiKeyPrincipal apiKey) {
        return new AuditActor(AuditActorType.API_KEY, apiKey.apiKeyId(), apiKey.name());
    }

    /** Something the application did on its own — the lifecycle reconciler, or a sweep. */
    public static AuditActor system() {
        return new AuditActor(AuditActorType.SYSTEM, null, "system");
    }

    public enum AuditActorType {
        USER,
        SYSTEM,
        API_KEY
    }

}
