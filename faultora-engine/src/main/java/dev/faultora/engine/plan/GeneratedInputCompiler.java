package dev.faultora.engine.plan;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.InputDefinition;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.schema.GenerationSpec;
import dev.faultora.schema.GenerationStrategy;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.schema.SchemaException;
import dev.faultora.schema.ValueGenerator;
import dev.faultora.spec.model.ScenarioStep;

import java.util.List;

/**
 * Compiles a step's {@code generate} block against the catalog's schemas.
 * <p>
 * Generation is checked here, not left to be discovered mid-run: the schema of
 * every named input must exist and must be satisfiable, which is proven by
 * generating a value with the run's own seed. A scenario asking for something
 * the contract cannot express fails before it sends a single request, and the
 * diagnostic names the field so the author knows which value to supply
 * explicitly instead.
 */
final class GeneratedInputCompiler {

    /** Name of the input carrying the request body. */
    private static final String BODY = "body";

    private final ApiCatalog catalog;
    private final SchemaCatalog schemas;
    private final ValueGenerator generator;
    private final long seed;

    GeneratedInputCompiler(ApiCatalog catalog, long seed) {
        this.catalog = catalog;
        this.schemas = new SchemaCatalog(catalog == null ? null : catalog.schemas());
        this.generator = new ValueGenerator(schemas);
        this.seed = seed;
    }

    /**
     * @return the compiled request, or null when the step declares none or the
     *         declaration is invalid — in which case diagnostics explain why
     */
    PlanNode.GenerationRequest compile(
            ScenarioStep step,
            dev.faultora.model.catalog.OperationDefinition operation,
            String phase,
            List<String> nodeIds,
            List<PlanDiagnostic> diagnostics
    ) {
        ScenarioStep.Generate generate = step.generate();
        if (generate == null) {
            return null;
        }
        String stepId = step.id();

        GenerationStrategy strategy = GenerationStrategy.from(generate.strategy());
        if (strategy == null) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "Unknown generation strategy: " + generate.strategy()));
            return null;
        }
        List<String> fields = generate.fields();
        if (fields == null || fields.isEmpty()) {
            diagnostics.add(PlanDiagnostic.error(phase, stepId,
                    "generate requires at least one input name, for example [body]"));
            return null;
        }
        boolean preferExamples = generate.preferExamples() == null || generate.preferExamples();
        GenerationSpec spec = new GenerationSpec(strategy, preferExamples);

        for (String field : fields) {
            SchemaId schemaId = schemaOf(operation, field);
            if (schemaId == null) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Cannot generate '" + field + "': operation "
                                + operation.id().value() + " declares no schema for it"));
                return null;
            }
            JsonNode schema = schemas.schema(schemaId);
            if (schema == null) {
                diagnostics.add(PlanDiagnostic.error(phase, stepId,
                        "Cannot generate '" + field + "': the catalog has no schema "
                                + schemaId.value()));
                return null;
            }
            // The check derives its seeds exactly as the run will, and covers
            // every node the step executes under. A schema with alternatives
            // can otherwise pass compilation on one branch and fail at run
            // time on another.
            for (String nodeId : nodeIds) {
                try {
                    // Proving the schema can be satisfied is the point: the
                    // value produced here is discarded, and the run generates
                    // its own.
                    generator.generate(schema, Seeds.derive(seed, nodeId, field), spec);
                } catch (SchemaException unsatisfiable) {
                    diagnostics.add(PlanDiagnostic.error(phase, stepId,
                            "Cannot generate '" + field + "' from schema "
                                    + schemaId.value() + ": " + unsatisfiable.getMessage()));
                    return null;
                }
            }
        }
        return new PlanNode.GenerationRequest(fields, strategy, preferExamples);
    }

    /**
     * The schema of one named input. The request body is described by the
     * operation itself; other inputs carry their own schema.
     */
    private SchemaId schemaOf(
            dev.faultora.model.catalog.OperationDefinition operation, String field) {
        if (BODY.equals(field) && operation.requestSchemaId() != null) {
            return operation.requestSchemaId();
        }
        InputDefinition input = operation.inputs() == null
                ? null : operation.inputs().get(field);
        return input == null ? null : input.schemaId();
    }

    /** Catalog the compiled requests resolve against, for the engine to reuse. */
    ApiCatalog catalog() {
        return catalog;
    }
}
