package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderAssertionProviderTest {

    private HeaderAssertionProvider provider;
    private AssertionContext context;

    @BeforeEach
    void setUp() {
        provider = new HeaderAssertionProvider();
        context = new AssertionContext("test-node", Map.of());
    }

    @Test
    void typeReturnsHeader() {
        assertThat(provider.type()).isEqualTo("header");
    }

    @Test
    void existsPassesWhenPresent() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("content-type", List.of("application/json")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void existsFailsWhenMissing() {
        EvidenceView evidence = new SimpleEvidence(200, Map.of(), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void equalsPasses() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("content-type", List.of("application/json")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "equals", "application/json"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void equalsFails() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("content-type", List.of("text/html")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "equals", "application/json"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void containsPasses() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("content-type", List.of("application/json; charset=utf-8")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "contains", "json"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void containsFails() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("content-type", List.of("text/html")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Content-Type", "contains", "json"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void countPasses() {
        EvidenceView evidence = new SimpleEvidence(200,
                Map.of("set-cookie", List.of("a=1", "b=2")), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("name", "Set-Cookie", "count", 2), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void missingHeaderNameIndeterminate() {
        EvidenceView evidence = new SimpleEvidence(200, Map.of(), null, null, 0);
        AssertionResult result = provider.evaluate("header",
                Map.of("exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }
}
