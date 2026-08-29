package com.freezhub.policy;

/**
 * The action a client is asking about.
 *
 * <p>Only {@code DEPLOY} exists (01-domain.md). It is named in the request anyway so the
 * contract does not have to change when a second action appears, and so an unsupported
 * one is a clear rejection rather than a silent assumption about what was meant.
 */
public enum PolicyAction {
    DEPLOY
}
