package dev.faultora.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void specModuleLoads() {
        assertTrue(true, "faultora-spec module participates in the build");
    }
}
