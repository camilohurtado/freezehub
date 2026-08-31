package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.shared.security.AesGcmSecretProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/*
 * The protector is imported explicitly because this is a slice: @DataJpaTest does not
 * scan @Component, but Hibernate still needs EncryptedStringConverter for the Integration
 * entity, and that converter needs a SecretProtector (FZ-049). The key is supplied for
 * the same reason a deployed environment must supply one — there is no default.
 */
@DataJpaTest(properties =
        "freezehub.secrets.encryption-key=ZGV2ZWxvcG1lbnQtb25seS1rZXktbm90LXNlY3JldCE=")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContainersConfig.class, AesGcmSecretProtector.class})
class OrganizationRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void persistsAndGeneratesIdAndTimestamps() {
        Organization saved = organizationRepository.saveAndFlush(new Organization("Acme Inc"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Organization found = organizationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Acme Inc");
    }

}
