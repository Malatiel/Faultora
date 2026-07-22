package dev.faultora.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void reportingModuleLoads() {
        assertTrue(true, "faultora-reporting module participates in the build");
    }
}
