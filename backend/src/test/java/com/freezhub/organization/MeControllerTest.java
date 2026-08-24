package com.freezhub.organization;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.shared.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenForAnUnknownIdentity() throws Exception {
        String token = TestTokens.forSubject(jwtEncoder, "no-such-subject");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolvesAnAuthenticatedUserToTheirOrganization() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme Inc"));
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), "cognito-subject-123", "admin@acme.test", UserRole.ADMINISTRATOR));

        String token = TestTokens.forSubject(jwtEncoder, "cognito-subject-123");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(user.getId().intValue())))
                .andExpect(jsonPath("$.organizationId", is(organization.getId().intValue())))
                .andExpect(jsonPath("$.email", is("admin@acme.test")))
                .andExpect(jsonPath("$.role", is("ADMINISTRATOR")));
    }

}
