package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAssertionProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaAssertionProvider provider = new SchemaAssertionProvider();

    @Test
    void aResponseMatchingItsSchemaPasses() throws Exception {
        AssertionResult result = evaluate(
                "{\"id\":\"pay-1\",\"amount\":250,\"currency\":\"EUR\"}",
                """
                {"type":"object","required":["id","amount"],
                 "properties":{
                   "id":{"type":"string"},
                   "amount":{"type":"integer","minimum":1},
                   "currency":{"type":"string","enum":["EUR","USD"]}}}
                """);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void driftIsNamedFieldByField() throws Exception {
        AssertionResult result = evaluate(
                "{\"id\":42,\"currency\":\"XYZ\"}",
                """
                {"type":"object","required":["id","amount"],
                 "properties":{
                   "id":{"type":"string"},
                   "amount":{"type":"integer"},
                   "currency":{"type":"string","enum":["EUR","USD"]}}}
                """);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        // A missing required field, a changed type, and a value outside its
        // enum are the three drifts this assertion exists to catch.
        assertThat(result.message()).contains("amount");
        assertThat(result.message()).contains("id");
        assertThat(result.message()).contains("currency");
    }

    @Test
    void anUndeclaredFieldFailsAClosedSchema() throws Exception {
        AssertionResult result = evaluate(
                "{\"id\":\"pay-1\",\"debug\":\"internal\"}",
                """
                {"type":"object","additionalProperties":false,
                 "properties":{"id":{"type":"string"}}}
                """);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.FAIL);
        assertThat(result.message()).contains("debug");
    }

    @Test
    void aNullableFieldMayBeNull() throws Exception {
        AssertionResult result = evaluate(
                "{\"id\":\"pay-1\",\"settledAt\":null}",
                """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "settledAt":{"type":"string","nullable":true}}}
                """);

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.PASS);
    }

    @Test
    void anUncapturedBodyIsIndeterminateRatherThanPassing() throws Exception {
        AssertionResult result = provider.evaluate(
                "schema", Map.of(), new EmptyEvidence(),
                new AssertionContext("check", Map.of(),
                        MAPPER.convertValue(MAPPER.readTree(
                                "{\"type\":\"object\"}"), Map.class)));

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
        assertThat(result.message()).contains("No response body");
    }

    @Test
    void anAbsentSchemaIsIndeterminate() {
        AssertionResult result = provider.evaluate(
                "schema", Map.of(),
                new SimpleEvidence(200, Map.of(),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        MAPPER.createObjectNode(), 1),
                new AssertionContext("check", Map.of()));

        assertThat(result.outcome()).isEqualTo(AssertionResult.Outcome.INDETERMINATE);
    }

    @SuppressWarnings("unchecked")
    private AssertionResult evaluate(String body, String schema) throws Exception {
        return provider.evaluate(
                "schema", Map.of(),
                new SimpleEvidence(200,
                        Map.of("content-type", java.util.List.of("application/json")),
                        body.getBytes(StandardCharsets.UTF_8), MAPPER.readTree(body), 5),
                new AssertionContext("check", Map.of(),
                        MAPPER.convertValue(MAPPER.readTree(schema), Map.class)));
    }
}
