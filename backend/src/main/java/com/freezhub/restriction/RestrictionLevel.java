package com.freezhub.restriction;

/**
 * Determines policy behaviour for a matching deployment (01-domain.md).
 * Evaluation itself is FZ-051; this only records the intent.
 */
public enum RestrictionLevel {

    /** Matching deployment stays allowed, but the restriction is reported. */
    ADVISORY,

    /** Matching deployment is blocked. */
    HARD_FREEZE
}
