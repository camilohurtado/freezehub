package com.freezhub.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.audit.AuditActor;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.AuthenticatedUser;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** When a key was last used, and what recording it costs (FZ-117). */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ApiKeyLastUsedTest {

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private Long organizationId;
    private String rawKey;
    private Long keyId;

    @BeforeEach
    void setUp() {
        organizationId = organizations
                .saveAndFlush(new Organization("Acme " + System.nanoTime())).getId();
        String subject = "subject-" + System.nanoTime();
        User admin = users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        var issued = apiKeys.create(organizationId, AuditActor.of(
                new AuthenticatedUser(admin.getId(), organizationId, admin.getEmail(), admin.getRole())),
                "gitlab-ci");
        rawKey = issued.rawKey();
        keyId = issued.apiKey().getId();
    }

    private LocalDate storedLastUsed() {
        return jdbc.queryForObject(
                "select last_used_on from api_key where id = ?", LocalDate.class, keyId);
    }

    @Test
    void aKeyThatHasNeverBeenUsedSaysSo() {
        // Null rather than the creation date: "never used" is the answer that makes a key
        // safe to revoke, and a date that looks like use would hide exactly that.
        assertThat(storedLastUsed()).isNull();
    }

    @Test
    void authenticatingRecordsTheDay() {
        apiKeys.authenticate(rawKey);

        assertThat(storedLastUsed()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void aSecondUseTheSameDayWritesNothing() {
        /*
         * The whole design of this column. The policy check runs once per pipeline run, so
         * stamping an instant would turn the deployment gate into a write path — and the
         * precision buys nothing, because the question is "is anything still using this?".
         *
         * Asserted through the version column rather than by counting SQL: a save that
         * changes nothing still bumps it, so this fails if the guard is removed.
         */
        apiKeys.authenticate(rawKey);
        Long firstWrite = jdbc.queryForObject(
                "select xmin::text::bigint from api_key where id = ?", Long.class, keyId);

        apiKeys.authenticate(rawKey);
        apiKeys.authenticate(rawKey);
        apiKeys.authenticate(rawKey);

        Long afterThreeMore = jdbc.queryForObject(
                "select xmin::text::bigint from api_key where id = ?", Long.class, keyId);
        assertThat(afterThreeMore).isEqualTo(firstWrite);
    }

    @Test
    void aUseOnANewDayIsRecorded() {
        jdbc.update("update api_key set last_used_on = ? where id = ?",
                LocalDate.now(ZoneOffset.UTC).minusDays(3), keyId);

        apiKeys.authenticate(rawKey);

        assertThat(storedLastUsed()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void arevokedKeyIsNotStamped() {
        // It cannot authenticate, so nothing used it — recording a use would make a
        // revoked key look live.
        apiKeyRepository.findById(keyId).ifPresent(key -> {
            key.revoke();
            apiKeyRepository.saveAndFlush(key);
        });

        assertThat(apiKeys.authenticate(rawKey)).isEmpty();
        assertThat(storedLastUsed()).isNull();
    }
}
