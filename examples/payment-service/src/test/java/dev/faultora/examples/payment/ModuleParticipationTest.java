package dev.faultora.examples.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleParticipationTest {
    @Test
    void paymentServiceModuleLoads() {
        assertTrue(true, "payment-service example module participates in the build");
    }
}
