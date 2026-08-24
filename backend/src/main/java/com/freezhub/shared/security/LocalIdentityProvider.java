package com.freezhub.shared.security;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/test substitute for IdentityProvider: no real Cognito user pool exists yet
 * (provisioning one is infrastructure work, FZ-063). Generates a fake subject instead
 * of calling AWS. A real Cognito-backed implementation (AdminCreateUser) belongs here
 * once that infrastructure exists — see FZ-016's known gap.
 */
@Component
@Profile("local")
public class LocalIdentityProvider implements IdentityProvider {

    @Override
    public String createUser(String email) {
        return "local-" + UUID.randomUUID();
    }

}
