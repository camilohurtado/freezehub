package com.freezhub.restriction;

/**
 * Kind of change a restriction applies to. The domain keeps this general enough to
 * support further restriction types later; the MVP only ever writes DEPLOYMENT_FREEZE
 * and never accepts the value from a client (01-domain.md).
 */
public enum RestrictionType {
    DEPLOYMENT_FREEZE
}
