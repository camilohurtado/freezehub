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

    /**
     * A team, application or environment was added, renamed or removed (FZ-072).
     *
     * <p>{@code CATALOG_RENAMED} is the one that earns its place. Because an unrecognised
     * name blocks (decision {@code D-14}), renaming an application turns every pipeline
     * still using the old name into a refusal — a wall of red in the console with, until
     * this existed, nothing anywhere explaining why it started.
     *
     * <p>Which kind of thing it was is {@code resourceType}, so three actions cover nine
     * cases without nine enum values that would only ever be read together.
     */
    CATALOG_CREATED,
    CATALOG_RENAMED,
    CATALOG_DELETED,

    /**
     * An application joined or left a team (FZ-072).
     *
     * <p>Audit-worthy because it silently changes what a team-scoped freeze covers,
     * without anybody touching the freeze.
     */
    APPLICATION_TEAM_ASSIGNED,
    APPLICATION_TEAM_UNASSIGNED,

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
