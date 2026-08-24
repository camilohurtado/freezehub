package com.freezhub.shared.security;

/**
 * Creates the external identity (Cognito) for an invited user and returns its subject
 * identifier, to be stored as users.cognito_subject. See 06-security.md / FZ-016.
 */
public interface IdentityProvider {

    String createUser(String email);

}
