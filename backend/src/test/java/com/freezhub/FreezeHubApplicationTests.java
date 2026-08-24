package com.freezhub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class FreezeHubApplicationTests {

    @Test
    void contextLoads() {
    }

}
