package dev.faultora.assertions.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.schema.SchemaValidator;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;

import java.util.List;
import java.util.Map;

/**
 * Checks a response body against the schema its contract declares for it.
 * <p>
 * This is the assertion that catches drift: a field that changed type, a
 * required field that stopped being sent, a value that left its enum. The
 * schema comes from the imported description and is resolved during plan
 * compilation, so a scenario cannot assert against a contract the catalog does
 * not have.
 * <p>
 * A body that was not captured makes the assertion indeterminate rather than
 * passing: the evidence policy decides what is kept, and an unevaluated check
 * must never read as a satisfied one.
 */
public class SchemaAssertionProvider implements AssertionProvider {

    /** Violations listed in a failure message before it is truncated. */
    private static final int REPORTED_VIOLATIONS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String type() {
        return "schema";
    }

    @Override
    public AssertionResult evaluate(
            String assertionType,
            Map<String, Object> params,
            EvidenceView evidence,
            AssertionContext context
    ) {
        if (context == null || context.schema() == null || context.schema().isEmpty()) {
            return AssertionResult.indeterminate(
                    "No response schema is declared for this step in the catalog");
        }
        if (evidence.responseJson().isEmpty()) {
            return AssertionResult.indeterminate(
                    evidence.responseBody().isPresent()
                            ? "Response body is not JSON, so it cannot be checked against a schema"
                            : "No response body was captured, so it cannot be checked "
                                    + "against a schema");
        }

        JsonNode schema = MAPPER.valueToTree(context.schema());
        List<SchemaValidator.Violation> violations =
                new SchemaValidator(new SchemaCatalog(Map.of()))
                        .validate(evidence.responseJson().get(), schema);

        if (violations.isEmpty()) {
            return AssertionResult.pass("Response matches its declared schema");
        }
        return AssertionResult.fail(
                describe(violations),
                Map.of("violations", violations.size()));
    }

    /**
     * Name what is wrong, bounded: a response that drifted far produces many
     * violations, and a message listing all of them stops being readable.
     */
    private String describe(List<SchemaValidator.Violation> violations) {
        StringBuilder message = new StringBuilder("Response violates its declared schema: ");
        for (int index = 0; index < Math.min(REPORTED_VIOLATIONS, violations.size()); index++) {
            if (index > 0) {
                message.append("; ");
            }
            message.append(violations.get(index));
        }
        if (violations.size() > REPORTED_VIOLATIONS) {
            message.append(" (and ")
                    .append(violations.size() - REPORTED_VIOLATIONS)
                    .append(" more)");
        }
        return message.toString();
    }
}
