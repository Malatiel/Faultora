package dev.faultora.engine.exec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.plan.PlanNode;
import dev.faultora.model.catalog.InputDefinition;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.schema.GenerationSpec;
import dev.faultora.schema.GenerationStrategy;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.schema.SchemaException;
import dev.faultora.schema.ValueGenerator;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.expression.ExpressionEvaluator;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces the inputs one operation is invoked with.
 * <p>
 * Inputs come from two places: what the scenario wrote, and what the schema
 * can generate. Explicit values are applied over generated ones, so a scenario
 * can generate a whole payload and still pin the fields it asserts on.
 * <p>
 * Resolution happens once per node execution, not once per attempt: a retry
 * has to resend the same request, and a poll has to ask the same question.
 * Generated values would otherwise change under a retry and quietly break the
 * idempotency scenarios this tool exists to run.
 */
public final class InputResolver {

    /** Name of the input carrying the request body. */
    private static final String BODY = "body";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator();

    /**
     * Resolve the inputs of one node.
     *
     * @throws SchemaException when a value cannot be generated; plan
     *                         compilation proves this cannot normally happen
     */
    public Map<String, Object> resolve(
            PlanNode.OperationNode node,
            NodeContext context,
            ExpressionContext expressionContext
    ) {
        Map<String, Object> explicit = expressionEvaluator.resolveInputs(
                node.inputExpressions(), expressionContext);
        PlanNode.GenerationRequest generation = node.generation();
        if (generation == null) {
            return explicit;
        }

        SchemaCatalog schemas = context.schemas();
        ValueGenerator generator = new ValueGenerator(schemas);
        boolean invalid = generation.strategy() == GenerationStrategy.INVALID;
        // An invalid payload is built valid first: the violation is introduced
        // after the step's own values are applied, or those values could put
        // the broken constraint back and leave the report claiming a violation
        // that never reached the target.
        GenerationSpec spec = new GenerationSpec(
                invalid ? GenerationStrategy.VALID : generation.strategy(),
                generation.preferExamples());

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String field : generation.fields()) {
            SchemaId schemaId = schemaOf(node.operation(), field);
            JsonNode schema = schemas.schema(schemaId);
            if (schema == null) {
                throw new SchemaException(field,
                        "no schema in the catalog for this input");
            }
            long seed = Seeds.derive(
                    context.plan().seed(), node.nodeId().value(), field);

            JsonNode value = generator.generate(schema, seed, spec).value();
            Object merged = merge(
                    MAPPER.convertValue(value, Object.class), explicit.get(field));

            String violation = null;
            if (invalid) {
                JsonNode mergedNode = MAPPER.valueToTree(merged);
                violation = generator.violate(
                        mergedNode, schema, seed, pinnedNames(explicit.get(field))).violation();
                merged = MAPPER.convertValue(mergedNode, Object.class);
            }

            context.journal().inputsGenerated(
                    node.nodeId(), field, generation.strategy().wireName(), seed,
                    schemaId.value(), bytesOf(MAPPER.valueToTree(merged)), violation);

            resolved.put(field, merged);
        }
        // Inputs the scenario states but does not generate pass through
        // untouched.
        explicit.forEach(resolved::putIfAbsent);
        return resolved;
    }

    /**
     * Apply an explicit value over a generated one. Objects merge field by
     * field so that pinning one property does not discard the rest of a
     * generated payload; anything else replaces.
     */
    static Object merge(Object generated, Object explicit) {
        if (explicit == null) {
            return generated;
        }
        if (generated instanceof Map<?, ?> generatedMap
                && explicit instanceof Map<?, ?> explicitMap) {
            Map<Object, Object> merged = new LinkedHashMap<>(generatedMap);
            explicitMap.forEach((key, value) ->
                    merged.put(key, merge(generatedMap.get(key), value)));
            return merged;
        }
        return explicit;
    }

    /** Property names the step supplied itself, which a violation avoids. */
    private static java.util.Set<String> pinnedNames(Object explicit) {
        if (!(explicit instanceof Map<?, ?> map)) {
            return java.util.Set.of();
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        map.keySet().forEach(key -> names.add(String.valueOf(key)));
        return names;
    }

    private SchemaId schemaOf(OperationDefinition operation, String field) {
        if (BODY.equals(field) && operation.requestSchemaId() != null) {
            return operation.requestSchemaId();
        }
        InputDefinition input = operation.inputs() == null
                ? null : operation.inputs().get(field);
        return input == null ? null : input.schemaId();
    }

    private static byte[] bytesOf(JsonNode value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException unexpected) {
            // The value came from Jackson's own node factory a moment ago.
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
