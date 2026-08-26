package com.freezhub.shared.security;

import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * Mints local/test JWTs shaped like a Cognito access token.
 *
 * <p>Delegates to {@link LocalTokenIssuer}, which the dev sign-in endpoint also uses, so
 * tests exercise exactly the token shape development runs against.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String forSubject(JwtEncoder jwtEncoder, String subject) {
        return LocalTokenIssuer.issue(jwtEncoder, subject);
    }

}
