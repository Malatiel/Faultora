package dev.faultora.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void integrationTestsModuleLoads() {
        assertTrue(true, "integration-tests module participates in the build");
    }
}
