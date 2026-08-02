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

    @Test
    void aNumberIsANumberHoweverItWasWritten() throws Exception {
        // 5 and 5.0 are one number, and a template resolves to text — a
        // scenario writing equals: "2500" means the amount, not a string that
        // happens to look like one. The tabular assertions already read them
        // this way, and two answers to "is this equal" is one too many.
        var evidence = jsonEvidence("{\"amount\": 2500, \"rate\": 5.0}");

        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "amount", "equals", "2500"), evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "rate", "equals", 5), evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "amount", "equals", 2501), evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void whatDistinguishesFiveFromTheTextFiveIsTheTypeCheck() throws Exception {
        var evidence = jsonEvidence("{\"amount\": 2500}");

        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "amount", "type", "number"), evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);
        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "amount", "type", "string"), evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.FAIL);
    }

    @Test
    void aRegexChecksTheShapeOfAValue() throws Exception {
        // Documented since this provider was written, implemented now. A
        // generated identifier is the case it exists for: the value is not
        // known, its shape is.
        var evidence = jsonEvidence("{\"id\": \"pay-4f2a91\", \"status\": \"settled\"}");

        assertThat(provider.evaluate("jsonpath",
                Map.of("path", "id", "matches", "^pay-[0-9a-f]{6}$"),
                evidence, context).outcome())
                .isEqualTo(AssertionResult.Outcome.PASS);

        AssertionResult wrongShape = provider.evaluate("jsonpath",
                Map.of("path", "status", "matches", "^pending$"), evidence, context);
        assertThat(wrongShape.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(wrongShape.message()).contains("settled");
    }

    @Test
    void aPatternThatIsNotOneSaysNothingAboutTheResponse() throws Exception {
        var evidence = jsonEvidence("{\"id\": \"pay-1\"}");

        AssertionResult result = provider.evaluate("jsonpath",
                Map.of("path", "id", "matches", "pay-[0-9"), evidence, context);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    private SimpleEvidence jsonEvidence(String json) throws Exception {
        return new SimpleEvidence(200, Map.of(),
                json.getBytes(), mapper.readTree(json), 0);
    }
}
