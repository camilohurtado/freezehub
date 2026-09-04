package com.freezhub.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Plan limits refuse creation and never apply retroactively (FZ-081, D-22). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class PlanLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private Long organizationId;
    private String token;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();

        String subject = UUID.randomUUID().toString();
        userRepository.saveAndFlush(
                new User(organizationId, subject, "admin@northwind.test", UserRole.ADMINISTRATOR));
        token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
    }

    private void onPlan(Plan plan) {
        subscriptionRepository.saveAndFlush(Subscription.provisioned(organizationId, plan));
    }

    private org.springframework.test.web.servlet.ResultActions createApplication(String name) throws Exception {
        return mockMvc.perform(post("/api/applications")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"));
    }

    @Test
    void refusesTheApplicationAfterTheLimitWith402() throws Exception {
        onPlan(Plan.STARTER);

        for (int i = 0; i < Plan.STARTER.applications(); i++) {
            createApplication("app-" + i).andExpect(status().isCreated());
        }

        // 402 rather than 400, 403 or 409: the request was well-formed and the caller is
        // permitted. It is the plan that refused, and no other status says that.
        createApplication("one-too-many")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.resource").value("applications"))
                .andExpect(jsonPath("$.limit").value(Plan.STARTER.applications()))
                .andExpect(jsonPath("$.current").value(Plan.STARTER.applications()));

        assertThat(applicationRepository.countByOrganizationId(organizationId))
                .isEqualTo(Plan.STARTER.applications().longValue());
    }

    @Test
    void aDowngradeBelowCurrentUsageDeletesNothing() throws Exception {
        // The rule D-22 exists for. Removing applications on a downgrade would start
        // blocking their pipelines, because an unregistered application is refused (D-14);
        // detaching them from scopes would silently narrow every freeze that named them.
        // A billing event must not un-freeze production.
        onPlan(Plan.GROWTH);
        for (int i = 0; i < 15; i++) {
            createApplication("app-" + i).andExpect(status().isCreated());
        }

        subscriptionRepository.findByOrganizationId(organizationId).ifPresent(subscriptionRepository::delete);
        onPlan(Plan.STARTER);

        assertThat(applicationRepository.countByOrganizationId(organizationId)).isEqualTo(15L);
        createApplication("sixteenth").andExpect(status().isPaymentRequired());
        assertThat(applicationRepository.countByOrganizationId(organizationId)).isEqualTo(15L);
    }

    @Test
    void anUnlimitedPlanNeverRefuses() throws Exception {
        onPlan(Plan.ENTERPRISE);
        for (int i = 0; i < Plan.SCALE.applications() + 1; i++) {
            createApplication("app-" + i).andExpect(status().isCreated());
        }
    }

    @Test
    void anEnterpriseOverrideIsWhatCounts() throws Exception {
        // Enterprise is priced per deal, so its limits live on the row rather than in the
        // enum. Without the override being read, this organization would be unlimited.
        Subscription subscription = Subscription.provisioned(organizationId, Plan.ENTERPRISE);
        subscription.setApplicationLimitOverride(2);
        subscriptionRepository.saveAndFlush(subscription);

        createApplication("first").andExpect(status().isCreated());
        createApplication("second").andExpect(status().isCreated());
        createApplication("third").andExpect(status().isPaymentRequired());
    }

    @Test
    void anOrganizationWithNoSubscriptionKeepsWorking() throws Exception {
        // A defect, logged as one — but it must not stop the customer. Refusing here would
        // make their API read-only because of a bug in our billing data, which is exactly
        // what D-21 refuses to do anywhere else.
        assertThat(subscriptionRepository.findByOrganizationId(organizationId)).isEmpty();
        createApplication("still-works").andExpect(status().isCreated());
    }
}
