package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security property behind FZ-035, asserted rather than assumed: the development
 * sign-in endpoint must not exist outside the {@code local} profile.
 *
 * <p>Note the deliberate absence of {@code @ActiveProfiles("local")}. That also removes
 * the {@code JwtDecoder} and {@code IdentityProvider} beans - which is the intended
 * fail-fast behaviour documented in 06-security.md - so both are stubbed here purely to
 * get a deployed-shaped context to boot. Nothing else about the application changes.
 */
@SpringBootTest(properties = "freezehub.lifecycle.enabled=false")
@AutoConfigureMockMvc
@Import({ContainersConfig.class, DevSignInAbsentOutsideLocalProfileTest.DeployedShapedStubs.class})
class DevSignInAbsentOutsideLocalProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void theDevSignInControllerIsNotRegistered() {
        assertThat(applicationContext.getBeanNamesForType(DevSignInController.class)).isEmpty();
    }

    @Test
    void theDevSignInSecurityChainIsNotRegistered() {
        assertThat(applicationContext.getBeanNamesForType(DevSignInSecurityConfig.class)).isEmpty();
    }

    @Test
    void theDevSignInPathIsNotPubliclyReachable() throws Exception {
        // Without the profile-scoped chain the path falls through to the main chain, which
        // demands authentication - so it is never an open door in a deployed environment.
        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anyone@acme.test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class DeployedShapedStubs {

        /** Stands in for the real Cognito decoder a deployed environment configures. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new InvalidBearerTokenException("stub decoder");
            };
        }

        /** Stands in for the Cognito-backed IdentityProvider still to be built (FZ-063). */
        @Bean
        IdentityProvider identityProvider() {
            return email -> {
                throw new UnsupportedOperationException("stub identity provider");
            };
        }
    }

}
