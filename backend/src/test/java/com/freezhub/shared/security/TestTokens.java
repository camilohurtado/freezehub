package com.freezhub.shared.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Mints local/test JWTs (see LocalJwtConfig) shaped like a Cognito access token. */
public final class TestTokens {

    private TestTokens() {
    }

    public static String forSubject(JwtEncoder jwtEncoder, String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("freezehub-local")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

}
