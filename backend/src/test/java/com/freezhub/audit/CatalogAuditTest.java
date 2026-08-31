package com.freezhub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
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
 * Catalog changes in the audit trail (FZ-072, fixes {@code OI-12}).
 *
 * <p>The one that matters: because an unrecognised name blocks (decision {@code D-14}),
 * renaming an application turns every pipeline still using the old name into a refusal.
 * Without this the deployment console shows a wall of red and nothing anywhere explains
 * when it started or who caused it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class CatalogAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long organizationId;
    private String token;
    private String email;

    @BeforeEach
    void givenAUser() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();
        String subject = "subject-" + System.nanoTime();
        email = subject + "@acme.test";
        userRepository.saveAndFlush(new User(organizationId, subject, email, UserRole.ADMINISTRATOR));
        token = TestTokens.forSubject(jwtEncoder, subject);
    }

    private long create(String path, String name) throws Exception {
        String body = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
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
    void recordsCreatingACatalogEntry() throws Exception {
        long id = create("/api/applications", "payments-api");

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.CATALOG_CREATED);
        assertThat(event.getResourceType()).isEqualTo(AuditResourceType.APPLICATION);
        assertThat(event.getResourceId()).isEqualTo(id);
        assertThat(event.getActorLabel()).isEqualTo(email);
        assertThat(event.getDetails()).contains("payments-api");
    }

    @Test
    void recordsRenamingAnApplicationWithBothNames() throws Exception {
        // The entry that answers "why did every deploy start being refused at 14:00?"
        long id = create("/api/applications", "payments-api");

        mockMvc.perform(patch("/api/applications/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"payments-service\"}"))
                .andExpect(status().isOk());

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.CATALOG_RENAMED);

        JsonNode details = objectMapper.readTree(event.getDetails());
        assertThat(details.path("name").path("from").asText()).isEqualTo("payments-api");
        assertThat(details.path("name").path("to").asText()).isEqualTo("payments-service");
    }

    @Test
    void recordsNothingWhenARenameChangedNothing() throws Exception {
        long id = create("/api/environments", "production");
        int before = trail().size();

        mockMvc.perform(patch("/api/environments/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"production\"}"))
                .andExpect(status().isOk());

        assertThat(trail()).hasSize(before);
    }

    @Test
    void recordsDeletingACatalogEntryWithTheNameItHad() throws Exception {
        // The name is the only part worth keeping: the id means nothing once the row is
        // gone, and "who deleted production?" is the question.
        long id = create("/api/environments", "staging");

        mockMvc.perform(delete("/api/environments/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        AuditEvent event = trail().getLast();
        assertThat(event.getAction()).isEqualTo(AuditAction.CATALOG_DELETED);
        assertThat(event.getDetails()).contains("staging");
    }

    @Test
    void recordsAnApplicationJoiningAndLeavingATeam() throws Exception {
        // Audit-worthy because it silently changes what a team-scoped freeze covers,
        // without anybody touching the freeze.
        long team = create("/api/teams", "Payments");
        long application = create("/api/applications", "payments-api");

        mockMvc.perform(put("/api/applications/" + application + "/teams/" + team)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        AuditEvent assigned = trail().getLast();
        assertThat(assigned.getAction()).isEqualTo(AuditAction.APPLICATION_TEAM_ASSIGNED);
        // Both names, so the entry reads without a join and survives either being renamed.
        assertThat(assigned.getDetails()).contains("payments-api").contains("Payments");

        mockMvc.perform(delete("/api/applications/" + application + "/teams/" + team)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(trail().getLast().getAction()).isEqualTo(AuditAction.APPLICATION_TEAM_UNASSIGNED);
    }

    @Test
    void doesNotRecordARepeatedAssignment() throws Exception {
        // Assignment is idempotent, so repeating the PUT must not fill the trail with
        // entries for nothing happening.
        long team = create("/api/teams", "Payments");
        long application = create("/api/applications", "payments-api");
        mockMvc.perform(put("/api/applications/" + application + "/teams/" + team)
                .header("Authorization", "Bearer " + token));
        int before = trail().size();

        mockMvc.perform(put("/api/applications/" + application + "/teams/" + team)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(trail()).hasSize(before);
    }

    @Test
    void recordsNothingWhenADeletionWasRefused() throws Exception {
        // A catalog entry a restriction still references cannot be deleted (409). Nothing
        // happened, so the trail must not claim it did.
        long environment = create("/api/environments", "production");
        String startsAt = java.time.Instant.now().plusSeconds(86400).toString();
        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Freeze\",\"reason\":\"r\",\"level\":\"HARD_FREEZE\",\"startsAt\":\""
                                + startsAt + "\",\"endsAt\":\"" + java.time.Instant.now().plusSeconds(172800)
                                + "\",\"scope\":{\"environmentIds\":[" + environment + "]}}"))
                .andExpect(status().isCreated());
        int before = trail().size();

        mockMvc.perform(delete("/api/environments/" + environment).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        assertThat(trail()).hasSize(before);
    }

}
