package com.freezhub.deployment;

/**
 * Why a check was refused. Null on ALLOW.
 *
 * <p>The distinction is the point: a freeze stopping a deployment is the product working,
 * while an unrecognised name is a pipeline misconfigured — or somebody trying a misspelling
 * to get through one (decision {@code D-14}). They read very differently in a console.
 */
public enum BlockedReason {
    RESTRICTION,
    UNREGISTERED
}
