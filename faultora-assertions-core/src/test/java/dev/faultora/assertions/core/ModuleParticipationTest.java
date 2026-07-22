package dev.faultora.assertions.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void assertionsCoreModuleLoads() {
        assertTrue(true, "faultora-assertions-core module participates in the build");
    }
}
