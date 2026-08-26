package com.freezhub.shared.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Mints local development tokens shaped like a Cognito access token (FZ-035).
 *
 * <p>Signing capability is gated by bean availability, not by discipline: the only
 * {@link JwtEncoder} in the application is defined by {@code LocalJwtConfig}, which is
 * {@code @Profile("local")}. No deployed environment activates that profile, so no
 * deployed environment can call this at all.
 *
 * <p>Single implementation on purpose - the dev sign-in endpoint and the test helper both
 * route through here so the claim shape cannot drift between them.
 */
public final class LocalTokenIssuer {

    /** Matches the local issuer LocalJwtConfig validates against; see 06-security.md. */
    public static final String ISSUER = "freezehub-local";

    private static final long TTL_HOURS = 1;

    private LocalTokenIssuer() {
    }

    public static String issue(JwtEncoder jwtEncoder, String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(TTL_HOURS, ChronoUnit.HOURS))
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

}
