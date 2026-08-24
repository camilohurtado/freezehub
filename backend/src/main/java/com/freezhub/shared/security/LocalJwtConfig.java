package com.freezhub.shared.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Local/test JWT issuer and validator, per 06-security.md: signed with a
 * locally-generated key, no AWS dependency. Never active outside the "local"
 * profile — every deployed environment validates against real Cognito instead
 * (see the default resource-server auto-configuration, driven by
 * spring.security.oauth2.resourceserver.jwt.issuer-uri).
 */
@Configuration
@Profile("local")
public class LocalJwtConfig {

    @Bean
    KeyPair localJwtKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair localJwtKeyPair) {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) localJwtKeyPair.getPublic()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair localJwtKeyPair) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) localJwtKeyPair.getPublic())
                .privateKey((RSAPrivateKey) localJwtKeyPair.getPrivate())
                .keyID("local")
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

}
