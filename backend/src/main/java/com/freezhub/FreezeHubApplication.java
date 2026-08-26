package com.freezhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FreezeHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FreezeHubApplication.class, args);
    }

}
