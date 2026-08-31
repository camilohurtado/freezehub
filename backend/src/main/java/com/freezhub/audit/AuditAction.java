package com.freezhub.audit;

/**
 * What was done (FZ-060).
 *
 * <p>A closed set rather than free text, so the trail can be filtered and counted rather
 * than grepped. Named for what happened, in the past tense, because an audit event is a
 * record of something already done — never an intent.
 */
public enum AuditAction {

    RESTRICTION_CREATED,
    /** Carries the fields that changed, before and after (decision {@code D-1}). */
    RESTRICTION_UPDATED,
    RESTRICTION_CANCELLED,
    /** System actor: the lifecycle reconciler, not a person. */
    RESTRICTION_ACTIVATED,
    RESTRICTION_COMPLETED,

    API_KEY_ISSUED,
    API_KEY_REVOKED,

    USER_INVITED,

    ORGANIZATION_SETTINGS_CHANGED,

    /**
     * A deployment was refused because it named an application or environment this
     * organization has not registered (decision {@code D-14}).
     *
     * <p>Recorded because refusing it in the moment is only half the answer: a misspelt
     * environment is a way to attempt deploying through a freeze, and one occurrence is a
     * typo while twenty is a pattern. Only refusals are recorded — a normal evaluation
     * happens on every deployment and would drown the trail it belongs to.
     */
    POLICY_BLOCKED_UNREGISTERED
}
