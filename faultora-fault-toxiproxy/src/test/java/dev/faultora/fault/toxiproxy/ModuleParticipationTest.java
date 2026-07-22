package dev.faultora.fault.toxiproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void toxiproxyFaultModuleLoads() {
        assertTrue(true, "faultora-fault-toxiproxy module participates in the build");
    }
}
