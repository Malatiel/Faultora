package dev.faultora.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void testkitModuleLoads() {
        assertTrue(true, "faultora-testkit module participates in the build");
    }
}
