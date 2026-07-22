package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPathAssertionProviderTest {

    private JsonPathAssertionProvider provider;
    private AssertionContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        provider = new JsonPathAssertionProvider();
        context = new AssertionContext("test-node", Map.of());
    }

    @Test
    void typeReturnsJsonpath() {
        assertThat(provider.type()).isEqualTo("jsonpath");
    }

    @Test
    void existsPassesWhenPresent() throws Exception {
        var evidence = jsonEvidence("{\"id\": \"pay-123\", \"amount\": 100}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void existsFailsWhenMissing() throws Exception {
        var evidence = jsonEvidence("{\"amount\": 100}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void equalsPasses() throws Exception {
        var evidence = jsonEvidence("{\"id\": \"pay-123\", \"amount\": 100}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "equals", "pay-123"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void equalsFails() throws Exception {
        var evidence = jsonEvidence("{\"id\": \"pay-123\", \"amount\": 100}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "equals", "pay-456"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void countPasses() throws Exception {
        var evidence = jsonEvidence("{\"items\": [1, 2, 3]}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "items", "count", 3), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void countFails() throws Exception {
        var evidence = jsonEvidence("{\"items\": [1, 2]}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "items", "count", 3), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void typeCheckPasses() throws Exception {
        var evidence = jsonEvidence("{\"name\": \"test\"}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "name", "type", "string"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void typeCheckFails() throws Exception {
        var evidence = jsonEvidence("{\"count\": 42}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "count", "type", "string"), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void uniquePasses() throws Exception {
        var evidence = jsonEvidence("{\"items\": [1, 2, 3]}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "items", "unique", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void uniqueFails() throws Exception {
        var evidence = jsonEvidence("{\"items\": [1, 2, 1]}");
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "items", "unique", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void noJsonBodyIndeterminate() {
        var evidence = new EmptyEvidence();
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @Test
    void missingPathIndeterminate() {
        var evidence = new EmptyEvidence();
        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("exists", true), evidence, context);
        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    private SimpleEvidence jsonEvidence(String json) throws Exception {
        return new SimpleEvidence(200, Map.of(),
                json.getBytes(), mapper.readTree(json), 0);
    }
}
