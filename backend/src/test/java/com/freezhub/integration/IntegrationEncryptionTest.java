package com.freezhub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Secret material is ciphertext in the database (FZ-049, fixes {@code OI-4}).
 *
 * <p>Asserted against the **raw column** through JDBC, deliberately bypassing JPA. Going
 * through the repository would only prove the converter is symmetric — it would pass just
 * as happily if nothing were encrypted at all. What matters here is what someone holding
 * a database connection or a backup actually sees.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class IntegrationEncryptionTest {

    private static final String SLACK_SECRET = "XXXXSUPERSECRETSLACKTOKENXXXX";
    private static final String SLACK_CONFIG =
            "{\"webhookUrl\":\"https://hooks.slack.com/services/T0/B0/" + SLACK_SECRET + "\"}";

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Long organizationId;

    @BeforeEach
    void givenAnOrganization() {
        organizationId = organizationRepository
                .saveAndFlush(new Organization("Acme " + System.nanoTime())).getId();
    }

    private String rawColumn(Long id, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from integration where id = ?", String.class, id);
    }

    @Test
    void storesAChannelCredentialAsCiphertext() {
        Integration slack = integrationRepository.saveAndFlush(
                new Integration(organizationId, IntegrationType.SLACK, SLACK_CONFIG));

        String stored = rawColumn(slack.getId(), "config");

        assertThat(stored).startsWith("fzenc1:");
        assertThat(stored).doesNotContain(SLACK_SECRET);
        assertThat(stored).doesNotContain("hooks.slack.com");
    }

    @Test
    void storesAWebhookSigningSecretAsCiphertext() {
        // The one that cannot be hashed instead, because HMAC needs the key itself —
        // which is exactly why OI-4 got sharper when FZ-048 added it.
        Integration webhook = integrationRepository.saveAndFlush(
                new Integration(organizationId, IntegrationType.WEBHOOK, "{\"url\":\"https://acme.test/hooks\"}"));

        String stored = rawColumn(webhook.getId(), "signing_secret");

        assertThat(stored).startsWith("fzenc1:");
        assertThat(stored).doesNotContain(webhook.getSigningSecret());
        assertThat(webhook.getSigningSecret()).startsWith("whsec_");
    }

    @Test
    void readsBackThePlaintextTheApplicationNeeds() {
        // Encryption is invisible above the persistence layer: senders still receive a
        // usable credential, which is the reason nothing else in the codebase changed.
        Integration webhook = integrationRepository.saveAndFlush(
                new Integration(organizationId, IntegrationType.WEBHOOK, "{\"url\":\"https://acme.test/hooks\"}"));
        String issuedSecret = webhook.getSigningSecret();
        integrationRepository.flush();

        Integration reloaded = integrationRepository.findById(webhook.getId()).orElseThrow();

        assertThat(reloaded.getConfig()).isEqualTo("{\"url\":\"https://acme.test/hooks\"}");
        assertThat(reloaded.getSigningSecret()).isEqualTo(issuedSecret);
    }

    @Test
    void keepsWorkingForRowsWrittenBeforeEncryptionExisted() {
        // A pre-FZ-049 row is plaintext with no scheme prefix. It must still be readable,
        // which is what makes this change need no bulk re-encryption.
        Integration slack = integrationRepository.saveAndFlush(
                new Integration(organizationId, IntegrationType.SLACK, SLACK_CONFIG));
        jdbcTemplate.update("update integration set config = ? where id = ?",
                SLACK_CONFIG, slack.getId());

        Integration reloaded = integrationRepository.findById(slack.getId()).orElseThrow();

        assertThat(reloaded.getConfig()).isEqualTo(SLACK_CONFIG);
    }

}
