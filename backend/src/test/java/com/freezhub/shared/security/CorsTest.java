package com.freezhub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Preflight behaviour, which nothing else catches: the frontend's own tests stub fetch,
 * so a missing CORS configuration only shows up as every request failing in a real
 * browser. This asserts the preflight is answered rather than rejected as unauthenticated
 * — an OPTIONS request carries no Authorization header by design.
 */
@SpringBootTest(properties = "freezehub.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class CorsTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void answersPreflightForAnAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/restrictions")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    void answersPreflightForTheDevSignInEndpoint() throws Exception {
        // Sign-in is cross-origin too, and it sits behind its own filter chain.
        mockMvc.perform(options("/api/dev/token")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    void refusesAnOriginThatIsNotConfigured() throws Exception {
        mockMvc.perform(options("/api/restrictions")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

}
