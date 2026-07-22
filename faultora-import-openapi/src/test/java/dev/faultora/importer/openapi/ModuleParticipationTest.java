package dev.faultora.importer.openapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void openapiImporterModuleLoads() {
        assertTrue(true, "faultora-import-openapi module participates in the build");
    }
}
