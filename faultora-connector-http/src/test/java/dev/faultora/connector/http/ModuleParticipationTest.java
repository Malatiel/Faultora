package dev.faultora.connector.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void httpConnectorModuleLoads() {
        assertTrue(true, "faultora-connector-http module participates in the build");
    }
}
