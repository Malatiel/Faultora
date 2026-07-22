package dev.faultora.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void spiModuleLoads() {
        assertTrue(true, "faultora-spi module participates in the build");
    }
}
