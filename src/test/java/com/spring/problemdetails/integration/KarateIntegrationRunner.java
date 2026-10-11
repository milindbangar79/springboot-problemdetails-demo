package com.spring.problemdetails.integration;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots the full Spring context on a random port and runs all Karate feature
 * files under src/test/resources/karate against the live server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KarateIntegrationRunner {

    @LocalServerPort
    int port;

    @Test
    void runAllKarateFeatures() {
        Results results = Runner.path("classpath:karate")
                .systemProperty("server.port", String.valueOf(port))
                .parallel(1);
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}
