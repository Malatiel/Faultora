package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StatusAssertionProviderTest {

    private StatusAssertionProvider provider;
    private AssertionContext context;

    @BeforeEach
    void setUp() {
        provider = new StatusAssertionProvider();
        context = new AssertionContext("test-node", Map.of());
    }

    @Test
    void typeReturnsStatus() {
        assertThat(provider.type()).isEqualTo("status");
    }

    @Test
    void exactMatchPasses() {
        EvidenceView evidence = evidence(200);
        AssertionResult result = provider.evaluate("status",
                Map.of("expected", 200), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void exactMatchFails() {
        EvidenceView evidence = evidence(404);
        AssertionResult result = provider.evaluate("status",
                Map.of("expected", 200), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void rangePasses() {
        EvidenceView evidence = evidence(201);
        AssertionResult result = provider.evaluate("status",
                Map.of("min", 200, "max", 299), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void rangeFails() {
        EvidenceView evidence = evidence(500);
        AssertionResult result = provider.evaluate("status",
                Map.of("min", 200, "max", 299), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void documentedPasses() {
        EvidenceView evidence = evidence(200);
        AssertionResult result = provider.evaluate("status",
                Map.of("documented", List.of(200, 201, 204)), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void documentedFails() {
        EvidenceView evidence = evidence(500);
        AssertionResult result = provider.evaluate("status",
                Map.of("documented", List.of(200, 201, 204)), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void noStatusCodeIndeterminate() {
        EvidenceView evidence = new EmptyEvidence();
        AssertionResult result = provider.evaluate("status",
                Map.of("expected", 200), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    private EvidenceView evidence(int statusCode) {
        return new SimpleEvidence(statusCode, Map.of(), null, null, 0);
    }
}
