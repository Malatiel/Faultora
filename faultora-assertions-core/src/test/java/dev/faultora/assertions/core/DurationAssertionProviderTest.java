package dev.faultora.assertions.core;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurationAssertionProviderTest {

    private DurationAssertionProvider provider;
    private AssertionContext context;

    @BeforeEach
    void setUp() {
        provider = new DurationAssertionProvider();
        context = new AssertionContext("test-node", Map.of());
    }

    @Test
    void typeReturnsDuration() {
        assertThat(provider.type()).isEqualTo("duration");
    }

    @Test
    void maxPassesWhenWithin() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 150);
        AssertionResult result = provider.evaluate("duration",
                Map.of("max", 500), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void maxFailsWhenExceeded() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 1500);
        AssertionResult result = provider.evaluate("duration",
                Map.of("max", 500), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void minPassesWhenAbove() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 100);
        AssertionResult result = provider.evaluate("duration",
                Map.of("min", 50), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void minFailsWhenBelow() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 10);
        AssertionResult result = provider.evaluate("duration",
                Map.of("min", 50), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void exactBoundaryPasses() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 500);
        AssertionResult result = provider.evaluate("duration",
                Map.of("max", 500), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void rangePassesWhenWithinBothBounds() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 150);
        AssertionResult result = provider.evaluate("duration",
                Map.of("min", 100, "max", 200), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void rangeFailsWhenBelowMinimum() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 50);
        AssertionResult result = provider.evaluate("duration",
                Map.of("min", 100, "max", 200), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("outside range");
    }

    @Test
    void noParamsIndeterminate() {
        var evidence = new SimpleEvidence(200, Map.of(), null, null, 100);
        AssertionResult result = provider.evaluate("duration",
                Map.of(), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }
}
