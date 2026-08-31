package com.freezhub.audit;

/** What the action was done to. */
public enum AuditResourceType {
    RESTRICTION,
    TEAM,
    APPLICATION,
    ENVIRONMENT,
    API_KEY,
    USER,
    ORGANIZATION,
    /** A policy evaluation, which refers to no stored row of its own. */
    POLICY
}
