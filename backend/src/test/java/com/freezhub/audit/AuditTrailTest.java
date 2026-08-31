package com.freezhub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.RestrictionLifecycleService;
import com.freezhub.shared.security.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/**
 * What the trail records, driven through the API rather than by calling the recorder
 * (FZ-060). An audit entry that only exists when a test writes it directly proves nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class AuditTrailTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private RestrictionLifecycleService restrictionLifecycleService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long organizationId;
    private String token;
    private String adminEmail;
    private Long environmentId;
    /** Fixed, so a body built twice is genuinely identical. */
    private Instant startsAt;

    @BeforeEach
    void givenAnAdministrator() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();
        String subject = "subject-" + System.nanoTime();
        adminEmail = subject + "@acme.test";
        userRepository.saveAndFlush(
                new User(organizationId, subject, adminEmail, UserRole.ADMINISTRATOR));
        token = TestTokens.forSubject(jwtEncoder, subject);
        environmentId = environmentRepository
                .saveAndFlush(new Environment(organizationId, "production")).getId();
        startsAt = Instant.now().plus(Duration.ofDays(2));
    }

    private String auth() {
        return "Bearer " + token;
    }

    private String restrictionBody(String name, String level) {
        return "{\"name\":\"" + name + "\",\"reason\":\"Revenue-critical period\",\"level\":\"" + level
                + "\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\"" + startsAt.plus(Duration.ofDays(1))
                + "\",\"scope\":{\"environmentIds\":[" + environmentId + "]}}";
    }

    private long createRestriction() throws Exception {
        String body = mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restrictionBody("Black Friday Freeze", "HARD_FREEZE")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private List<AuditEvent> trail() {
        return auditRepository.findAll().stream()
                .filter(event -> event.getOrganizationId().equals(organizationId))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Test
    void recordsWhoCreatedARestriction() throws Exception {
        long id = createRestriction();

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.RESTRICTION_CREATED);
        assertThat(event.getResourceId()).isEqualTo(id);
        // The label is captured at the time, so the entry stays readable if the user is
        // later removed — which is why there is no foreign key to users.
        assertThat(event.getActorLabel()).isEqualTo(adminEmail);
        assertThat(event.getActorType()).isEqualTo(AuditActor.AuditActorType.USER);
    }

    @Test
    void recordsWhatChangedOnAnUpdate() throws Exception {
        // The point of D-1: "someone edited this freeze" is not an answer to anything.
        long id = createRestriction();

        mockMvc.perform(put("/api/restrictions/" + id)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restrictionBody("Renamed Freeze", "ADVISORY")))
                .andExpect(status().isOk());

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.RESTRICTION_UPDATED);

        JsonNode details = objectMapper.readTree(event.getDetails());
        assertThat(details.path("name").path("from").asText()).isEqualTo("Black Friday Freeze");
        assertThat(details.path("name").path("to").asText()).isEqualTo("Renamed Freeze");
        assertThat(details.path("level").path("from").asText()).isEqualTo("HARD_FREEZE");
        assertThat(details.path("level").path("to").asText()).isEqualTo("ADVISORY");
        // Untouched fields stay out of it.
        assertThat(details.has("reason")).isFalse();
    }

    @Test
    void recordsScopeChanges() throws Exception {
        // Scope is where an edit does the most damage — narrowing a freeze quietly stops
        // it covering something — so the diff has to cover the collections, not just the
        // scalar fields.
        long id = createRestriction();
        Long otherEnvironment = environmentRepository
                .saveAndFlush(new Environment(organizationId, "staging")).getId();

        mockMvc.perform(put("/api/restrictions/" + id)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Black Friday Freeze\",\"reason\":\"Revenue-critical period\","
                                + "\"level\":\"HARD_FREEZE\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\""
                                + startsAt.plus(Duration.ofDays(1)) + "\",\"scope\":{\"environmentIds\":["
                                + otherEnvironment + "]}}"))
                .andExpect(status().isOk());

        JsonNode details = objectMapper.readTree(trail().getLast().getDetails());
        assertThat(details.has("environmentIds")).isTrue();
        assertThat(details.path("environmentIds").path("from").toString()).contains(environmentId.toString());
        assertThat(details.path("environmentIds").path("to").toString()).contains(otherEnvironment.toString());
    }

    @Test
    void recordsNothingWhenAnUpdateChangedNothing() throws Exception {
        // PUT is a replacement, not a diff, so an identical body succeeds — but there is
        // nothing to remember about it.
        long id = createRestriction();
        int before = trail().size();

        mockMvc.perform(put("/api/restrictions/" + id)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restrictionBody("Black Friday Freeze", "HARD_FREEZE")))
                .andExpect(status().isOk());

        assertThat(trail()).hasSize(before);
    }

    @Test
    void recordsCancellation() throws Exception {
        long id = createRestriction();

        mockMvc.perform(post("/api/restrictions/" + id + "/cancel").header("Authorization", auth()))
                .andExpect(status().isOk());

        assertThat(trail().getLast().getAction()).isEqualTo(AuditAction.RESTRICTION_CANCELLED);
    }

    @Test
    void recordsLifecycleTransitionsAgainstTheSystem() throws Exception {
        // Nobody activated this; time did. Worth recording anyway — "when did the freeze
        // actually take effect" is the question asked after an incident, and the
        // reconciler's interval means it is not exactly startsAt.
        Instant openedAlready = Instant.now().minus(Duration.ofMinutes(1));
        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Already open\",\"reason\":\"r\",\"level\":\"HARD_FREEZE\","
                                + "\"startsAt\":\"" + openedAlready + "\",\"endsAt\":\""
                                + openedAlready.plus(Duration.ofDays(1)) + "\",\"scope\":{\"environmentIds\":["
                                + environmentId + "]}}"))
                .andExpect(status().isCreated());

        restrictionLifecycleService.reconcile(Instant.now());

        AuditEvent activated = trail().stream()
                .filter(event -> event.getAction() == AuditAction.RESTRICTION_ACTIVATED)
                .findFirst().orElseThrow();
        assertThat(activated.getActorType()).isEqualTo(AuditActor.AuditActorType.SYSTEM);
        assertThat(activated.getActorId()).isNull();
    }

    @Test
    void recordsApiKeyIssueAndRevocationWithoutRecordingTheKey() throws Exception {
        String created = mockMvc.perform(post("/api/api-keys")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gitlab-ci\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode key = objectMapper.readTree(created);

        AuditEvent issued = trail().getLast();
        assertThat(issued.getAction()).isEqualTo(AuditAction.API_KEY_ISSUED);
        // The credential itself must never reach the trail; the prefix identifies it.
        assertThat(issued.getDetails()).contains(key.get("keyPrefix").asText());
        assertThat(issued.getDetails()).doesNotContain(key.get("key").asText());

        mockMvc.perform(post("/api/api-keys/" + key.get("id").asLong() + "/revoke")
                        .header("Authorization", auth()))
                .andExpect(status().isOk());

        assertThat(trail().getLast().getAction()).isEqualTo(AuditAction.API_KEY_REVOKED);
    }

    @Test
    void recordsAnOrganizationSettingChange() throws Exception {
        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startingSoonLeadTimeMinutes\":120}"))
                .andExpect(status().isOk());

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.ORGANIZATION_SETTINGS_CHANGED);
        assertThat(event.getDetails()).contains("1440").contains("120");
    }

    @Test
    void exposesTheTrailNewestFirstToAdministratorsOnly() throws Exception {
        createRestriction();

        mockMvc.perform(get("/api/audit").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action", is("RESTRICTION_CREATED")))
                .andExpect(jsonPath("$[0].actorLabel", is(adminEmail)));

        String memberSubject = "member-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organizationId, memberSubject, memberSubject + "@acme.test", UserRole.MEMBER));

        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, memberSubject)))
                .andExpect(status().isForbidden());
    }

    @Test
    void neverShowsAnotherOrganizationsTrail() throws Exception {
        createRestriction();

        Organization other =
                organizationRepository.saveAndFlush(new Organization("Other " + System.nanoTime()));
        String otherSubject = "other-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(other.getId(), otherSubject, otherSubject + "@other.test", UserRole.ADMINISTRATOR));

        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, otherSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void showsEverythingThatHappenedToOneRestriction() throws Exception {
        long id = createRestriction();
        mockMvc.perform(post("/api/restrictions/" + id + "/cancel").header("Authorization", auth()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit/resource")
                        .header("Authorization", auth())
                        .param("resourceType", "RESTRICTION")
                        .param("resourceId", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].action", is("RESTRICTION_CANCELLED")))
                .andExpect(jsonPath("$[1].action", is("RESTRICTION_CREATED")));
    }

}
