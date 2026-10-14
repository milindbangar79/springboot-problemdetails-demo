package com.spring.problemdetails.integration;

import com.intuit.karate.junit5.Karate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full Spring context on a random port and runs all Karate feature
 * files under src/test/resources/karate against the live server.
 *
 * Uses @Karate.Test (JUnit 5 TestFactory) instead of Runner.path().parallel()
 * to avoid a CompletableFuture hang on Java 21+ virtual-thread schedulers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KarateIntegrationRunner {

    @LocalServerPort
    int port;

    @Karate.Test
    Karate allFeatures() {
        System.setProperty("server.port", String.valueOf(port));
        return Karate.run("classpath:karate");
    }
}
