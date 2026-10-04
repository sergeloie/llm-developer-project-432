package com.carddraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. Deliberately in the root package so that Spring Boot's test context discovery
 * finds it by searching upward from any test package.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CardDraftingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardDraftingApplication.class, args);
    }
}
