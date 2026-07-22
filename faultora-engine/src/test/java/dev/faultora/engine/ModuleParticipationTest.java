package dev.faultora.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void engineModuleLoads() {
        assertTrue(true, "faultora-engine module participates in the build");
    }
}
