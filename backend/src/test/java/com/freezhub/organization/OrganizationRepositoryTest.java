package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ContainersConfig.class)
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
