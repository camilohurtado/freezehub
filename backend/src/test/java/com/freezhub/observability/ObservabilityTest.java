package com.freezhub.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import com.freezhub.shared.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Request correlation, probes, and the metrics that make failures visible (FZ-062). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private String token;

    @BeforeEach
    void givenAUser() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        token = TestTokens.forSubject(jwtEncoder, subject);
    }

    @Test
    void givesEveryResponseARequestId() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[A-Za-z0-9._:-]+")));
    }

    @Test
    void honoursARequestIdSuppliedByTheCaller() throws Exception {
        // So a load balancer's or a caller's own tracing id survives into these logs and
        // the two sides can be matched up.
        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token)
                        .header(RequestIdFilter.HEADER, "caller-supplied-id"))
                .andExpect(header().string(RequestIdFilter.HEADER, "caller-supplied-id"));
    }

    @Test
    void sanitisesASuppliedRequestIdBeforeItReachesALogLine() throws Exception {
        // The value goes into log files. An unbounded header is somebody else's newline
        // injected into them, and a forged log line is worse than a missing one.
        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token)
                        .header(RequestIdFilter.HEADER, "bad\nid WARN faked-log-line"))
                .andExpect(header().string(RequestIdFilter.HEADER, not(org.hamcrest.Matchers.containsString("\n"))))
                .andExpect(header().string(RequestIdFilter.HEADER, "badidWARNfaked-log-line"));
    }

    @Test
    void putsTheRequestIdInTheErrorBodySoAReportCanBeLookedUp() throws Exception {
        // The whole point of the id: "it failed at about three o'clock" becomes exact.
        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + token)
                        .header(RequestIdFilter.HEADER, "known-request-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId", is("known-request-id")));
    }

    @Test
    void exposesLivenessAndReadinessWithoutAuthentication() throws Exception {
        // The load balancer has no credential. Readiness is what it reads, and an exact
        // match on /actuator/health would have answered these with a 401.
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void keepsMetricsBehindAuthentication() throws Exception {
        // The counters are aggregate, not per tenant, so they are not for every member of
        // every organization to browse.
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void keepsMetricsFromOrdinaryMembers() throws Exception {
        /*
         * The sentence above this test was true of the intent and false of the code
         * (`FZ-065`): authentication was the only bar, so any member of any organization
         * could read how many deployment checks every customer makes, and the JVM's
         * internals besides. An administrator is not the right bar either — these figures
         * are not tenant-scoped at all — but it is the one that costs nothing here. The
         * answer is a management port nothing outside can reach (`OI-21`).
         */
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Contoso " + System.nanoTime()));
        String subject = "member-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@contoso.test", UserRole.MEMBER));
        String memberToken = TestTokens.forSubject(jwtEncoder, subject);

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // The load balancer still gets its answer without a credential at all.
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void countsWhatTheDeploymentGateDecided() throws Exception {
        // Registered as soon as the application starts, so a dashboard has a series to
        // draw before the first deployment rather than a gap.
        mockMvc.perform(get("/actuator/metrics/freezehub.policy.evaluations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'reason')].values[0]").exists());
    }

    @Test
    void countsWhatHappenedToAnnouncements() throws Exception {
        mockMvc.perform(get("/actuator/metrics/freezehub.notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotLeakTheRequestIdBetweenRequests() throws Exception {
        // Threads are pooled. Without the MDC being cleared, the next request on this
        // thread would inherit the previous one's id — worse than having none, because it
        // is confidently wrong.
        mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + token)
                .header(RequestIdFilter.HEADER, "first-request"));

        assertThat(RequestIdFilter.current()).isNull();
    }

}
