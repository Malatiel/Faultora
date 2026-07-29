package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.identifier.SchemaId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaCatalog emptyCatalog = new SchemaCatalog(Map.of());
    private final ValueGenerator generator = new ValueGenerator(emptyCatalog);

    @Test
    void generatesEveryDeclaredPropertyForTheValidStrategy() throws Exception {
        JsonNode schema = schema("""
                {"type":"object",
                 "required":["amount"],
                 "properties":{
                   "amount":{"type":"integer","minimum":1,"maximum":100},
                   "currency":{"type":"string","enum":["EUR","USD"]},
                   "note":{"type":"string"}}}
                """);

        JsonNode value = generator.generate(schema, 42L, GenerationSpec.DEFAULT).value();

        assertThat(value.fieldNames()).toIterable().containsExactly("amount", "currency", "note");
        assertThat(value.get("amount").asInt()).isBetween(1, 100);
        assertThat(value.get("currency").asText()).isIn("EUR", "USD");
    }

    @Test
    void theSameSeedReproducesTheSamePayload() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","properties":{
                   "amount":{"type":"integer","minimum":1,"maximum":1000000},
                   "id":{"type":"string","format":"uuid"},
                   "tags":{"type":"array","items":{"type":"string"},"minItems":2}}}
                """);

        JsonNode first = generator.generate(schema, 7L, GenerationSpec.DEFAULT).value();
        JsonNode second = generator.generate(schema, 7L, GenerationSpec.DEFAULT).value();
        JsonNode other = generator.generate(schema, 8L, GenerationSpec.DEFAULT).value();

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    void theBoundaryStrategySendsTheSmallestAcceptedPayloadAtItsLimits() throws Exception {
        JsonNode schema = schema("""
                {"type":"object",
                 "required":["amount"],
                 "properties":{
                   "amount":{"type":"integer","minimum":5,"maximum":900},
                   "label":{"type":"string","minLength":3,"maxLength":40},
                   "optional":{"type":"string"}}}
                """);

        JsonNode value = generator.generate(
                schema, 42L, new GenerationSpec(GenerationStrategy.BOUNDARY, true)).value();

        assertThat(value.has("optional")).isFalse();
        assertThat(value.get("amount").asInt()).isEqualTo(5);
    }

    @Test
    void anAuthoredExampleIsPreferredOverAGeneratedValue() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","properties":{
                   "currency":{"type":"string","example":"CHF"}}}
                """);

        assertThat(generator.generate(schema, 1L, GenerationSpec.DEFAULT)
                .value().get("currency").asText()).isEqualTo("CHF");
        assertThat(generator.generate(schema, 1L,
                new GenerationSpec(GenerationStrategy.VALID, false))
                .value().get("currency").asText()).isNotEqualTo("CHF");
    }

    @Test
    void anExampleThatViolatesItsOwnSchemaIsNotSentVerbatim() throws Exception {
        // Stale examples are common in real documents; honouring one blindly
        // would send a payload the contract rejects.
        JsonNode schema = schema("""
                {"type":"object","required":["currency"],
                 "properties":{
                   "currency":{"type":"string","enum":["EUR","USD"],"example":"XYZ"},
                   "amount":{"type":"integer","minimum":100,"example":5}}}
                """);

        JsonNode value = generator.generate(schema, 1L, GenerationSpec.DEFAULT).value();

        assertThat(new SchemaValidator(emptyCatalog).validate(value, schema)).isEmpty();
        assertThat(value.get("currency").asText()).isIn("EUR", "USD");
        assertThat(value.get("amount").asInt()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void aRangeNarrowerThanTheDefaultPrecisionStillProducesValidNumbers() throws Exception {
        // Rates and shares live below two decimals; rounding to a fixed scale
        // put every generated value outside the accepted range.
        JsonNode schema = schema("""
                {"type":"object","required":["rate"],
                 "properties":{"rate":{"type":"number","minimum":0.001,"maximum":0.004}}}
                """);
        SchemaValidator validator = new SchemaValidator(emptyCatalog);

        for (long seed = 0; seed < 100; seed++) {
            JsonNode value = generator.generate(schema, seed, GenerationSpec.DEFAULT).value();
            assertThat(validator.validate(value, schema))
                    .describedAs("seed %d produced %s", seed, value)
                    .isEmpty();
        }
    }

    @Test
    void serverManagedPropertiesAreNotSentInARequest() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","required":["amount"],
                 "properties":{
                   "id":{"type":"string","readOnly":true},
                   "createdAt":{"type":"string","format":"date-time","readOnly":true},
                   "amount":{"type":"integer","minimum":1}}}
                """);

        JsonNode value = generator.generate(schema, 1L, GenerationSpec.DEFAULT).value();

        assertThat(value.has("id")).isFalse();
        assertThat(value.has("createdAt")).isFalse();
        assertThat(value.has("amount")).isTrue();
    }

    @Test
    void aViolationAvoidsThePropertiesTheCallerPinned() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","required":["amount","currency"],
                 "properties":{
                   "amount":{"type":"integer","minimum":1},
                   "currency":{"type":"string","enum":["EUR","USD"]}}}
                """);
        JsonNode value = generator.generate(schema, 3L, GenerationSpec.DEFAULT).value();

        GenerationResult result = generator.violate(
                value, schema, 3L, java.util.Set.of("currency"));

        assertThat(result.violation()).contains("amount");
        assertThat(value.has("currency")).isTrue();
        assertThat(new SchemaValidator(emptyCatalog).validate(value, schema)).isNotEmpty();
    }

    @Test
    void theInvalidStrategyBreaksExactlyOneConstraintAndSaysWhich() throws Exception {
        JsonNode schema = schema("""
                {"type":"object",
                 "required":["amount"],
                 "properties":{"amount":{"type":"integer","minimum":1}}}
                """);

        GenerationResult result = generator.generate(
                schema, 3L, new GenerationSpec(GenerationStrategy.INVALID, true));

        assertThat(result.isViolating()).isTrue();
        assertThat(result.violation()).contains("amount");
        assertThat(new SchemaValidator(emptyCatalog).validate(result.value(), schema))
                .hasSize(1);
    }

    @Test
    void aReferencedSchemaIsResolvedThroughTheCatalog() throws Exception {
        SchemaCatalog catalog = new SchemaCatalog(Map.of(
                new SchemaId("Money"), new DataSchema(
                        new SchemaId("Money"), "object", "#/components/schemas/Money",
                        MAPPER.convertValue(schema("""
                                {"type":"object","required":["amount"],
                                 "properties":{"amount":{"type":"integer","minimum":1}}}
                                """), Map.class))));
        JsonNode schema = schema("""
                {"type":"object","properties":{
                   "total":{"$ref":"#/components/schemas/Money"}}}
                """);

        JsonNode value = new ValueGenerator(catalog)
                .generate(schema, 5L, GenerationSpec.DEFAULT).value();

        assertThat(value.get("total").get("amount").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inliningMakesASchemaUnderstandableWithoutTheCatalog() throws Exception {
        SchemaCatalog catalog = new SchemaCatalog(Map.of(
                new SchemaId("Payment"), new DataSchema(
                        new SchemaId("Payment"), "object", "#/components/schemas/Payment",
                        MAPPER.convertValue(schema("""
                                {"type":"object","required":["id"],
                                 "properties":{"id":{"type":"string"}}}
                                """), Map.class))));
        JsonNode listOfPayments = schema("""
                {"type":"array","items":{"$ref":"#/components/schemas/Payment"}}
                """);

        JsonNode inlined = catalog.inline(listOfPayments);

        // A consumer holding only this schema — an assertion checking a
        // response — has no way to follow a reference later.
        assertThat(inlined.toString()).doesNotContain("$ref");
        assertThat(new SchemaValidator(new SchemaCatalog(Map.of()))
                .validate(MAPPER.readTree("[{\"id\":\"pay-1\"}]"), inlined)).isEmpty();
        assertThat(new SchemaValidator(new SchemaCatalog(Map.of()))
                .validate(MAPPER.readTree("[{\"amount\":1}]"), inlined)).isNotEmpty();
    }

    @Test
    void aSelfReferencingSchemaStopsExpandingInsteadOfLooping() throws Exception {
        SchemaCatalog catalog = new SchemaCatalog(Map.of(
                new SchemaId("Node"), new DataSchema(
                        new SchemaId("Node"), "object", "#/components/schemas/Node",
                        MAPPER.convertValue(schema("""
                                {"type":"object","properties":{
                                   "name":{"type":"string"},
                                   "child":{"$ref":"#/components/schemas/Node"}}}
                                """), Map.class))));

        JsonNode inlined = catalog.inline(
                schema("{\"$ref\":\"#/components/schemas/Node\"}"));

        assertThat(inlined.at("/properties/name/type").asText()).isEqualTo("string");
        assertThat(inlined.toString()).doesNotContain("$ref");
    }

    @Test
    void aRegularExpressionIsRefusedRatherThanGuessedAt() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","required":["iban"],
                 "properties":{"iban":{"type":"string","pattern":"^[A-Z]{2}[0-9]{20}$"}}}
                """);

        assertThatThrownBy(() -> generator.generate(schema, 1L, GenerationSpec.DEFAULT))
                .isInstanceOf(SchemaException.class)
                .hasMessageContaining("iban")
                .hasMessageContaining("supply this value explicitly");
    }

    @Test
    void anOptionalPropertyThatCannotBeGeneratedIsLeftOut() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","required":["amount"],
                 "properties":{
                   "amount":{"type":"integer"},
                   "iban":{"type":"string","pattern":"^[A-Z]{2}[0-9]{20}$"}}}
                """);

        JsonNode value = generator.generate(schema, 1L, GenerationSpec.DEFAULT).value();

        assertThat(value.has("amount")).isTrue();
        assertThat(value.has("iban")).isFalse();
    }

    @Test
    void anImpossibleRangeIsReportedInsteadOfProducingAnInvalidValue() throws Exception {
        JsonNode schema = schema("""
                {"type":"object","required":["amount"],
                 "properties":{"amount":{"type":"integer","minimum":10,"maximum":5}}}
                """);

        assertThatThrownBy(() -> generator.generate(schema, 1L, GenerationSpec.DEFAULT))
                .isInstanceOf(SchemaException.class)
                .hasMessageContaining("below minimum");
    }

    /**
     * The acceptance criterion of the milestone: whatever the generator emits
     * under the valid strategy satisfies the schema it came from.
     */
    @Test
    void everyGeneratedValidPayloadPassesItsOwnSchema() throws Exception {
        List<String> corpus = List.of(
                """
                {"type":"object","required":["amount","currency"],
                 "properties":{
                   "amount":{"type":"integer","minimum":1,"maximum":1000000},
                   "currency":{"type":"string","enum":["EUR","USD","GBP"]},
                   "reference":{"type":"string","minLength":4,"maxLength":12},
                   "createdAt":{"type":"string","format":"date-time"}}}
                """,
                """
                {"type":"object","properties":{
                   "items":{"type":"array","minItems":2,"maxItems":4,
                            "items":{"type":"object","required":["sku"],
                                     "properties":{"sku":{"type":"string"},
                                                   "qty":{"type":"integer","minimum":1,"maximum":9}}}}}}
                """,
                """
                {"allOf":[
                   {"type":"object","required":["id"],"properties":{"id":{"type":"string","format":"uuid"}}},
                   {"type":"object","properties":{"active":{"type":"boolean"}}}]}
                """,
                """
                {"type":"object","properties":{
                   "amount":{"type":"number","minimum":0.5,"maximum":99.5},
                   "kind":{"const":"transfer"},
                   "channel":{"type":"string","enum":["web","mobile"]}}}
                """,
                // Stale examples: honouring them blindly would send payloads
                // the schema itself rejects.
                """
                {"type":"object","required":["currency","amount"],
                 "properties":{
                   "currency":{"type":"string","enum":["EUR","USD"],"example":"XYZ"},
                   "amount":{"type":"integer","minimum":100,"maximum":200,"example":5},
                   "note":{"type":"string","minLength":3,"example":"ok"}}}
                """,
                // Both spellings of an exclusive bound, on both numeric types.
                """
                {"type":"object","required":["ratio","score"],
                 "properties":{
                   "ratio":{"type":"integer","minimum":10,"exclusiveMinimum":true,"maximum":20},
                   "score":{"type":"integer","exclusiveMinimum":0,"exclusiveMaximum":5}}}
                """,
                """
                {"type":"object","required":["rate","share"],
                 "properties":{
                   "rate":{"type":"number","minimum":0.5,"exclusiveMinimum":true,"maximum":9.0},
                   "share":{"type":"number","exclusiveMinimum":0.0,"exclusiveMaximum":1.0},
                   "fee":{"type":"number","minimum":0.001,"maximum":0.004}}}
                """);

        SchemaValidator validator = new SchemaValidator(emptyCatalog);
        for (String document : corpus) {
            JsonNode schema = schema(document);
            for (long seed = 0; seed < 50; seed++) {
                JsonNode value = generator.generate(schema, seed, GenerationSpec.DEFAULT).value();
                assertThat(validator.validate(value, schema))
                        .describedAs("seed %d of %s", seed, document)
                        .isEmpty();

                JsonNode boundary = generator.generate(
                        schema, seed, new GenerationSpec(GenerationStrategy.BOUNDARY, true))
                        .value();
                assertThat(validator.validate(boundary, schema))
                        .describedAs("boundary seed %d of %s", seed, document)
                        .isEmpty();
            }
        }
    }

    private JsonNode schema(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
