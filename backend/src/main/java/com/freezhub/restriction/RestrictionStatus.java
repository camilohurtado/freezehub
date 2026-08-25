package com.freezhub.restriction;

/**
 * Restriction lifecycle state (01-domain.md).
 *
 * <p>DRAFT is reserved by the domain but deliberately not implemented until explicitly
 * requested. Transitions between these states are FZ-025; FZ-020 only ever creates
 * SCHEDULED.
 */
public enum RestrictionStatus {
    SCHEDULED,
    ACTIVE,
    COMPLETED,
    CANCELLED
}
