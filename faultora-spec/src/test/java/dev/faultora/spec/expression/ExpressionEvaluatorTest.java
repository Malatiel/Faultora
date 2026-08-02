package dev.faultora.spec.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator;
    private ExpressionContext context;

    @BeforeEach
    void setUp() {
        evaluator = new ExpressionEvaluator();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode paymentOutput = mapper.createObjectNode();
        paymentOutput.put("id", "pay-123");
        paymentOutput.put("status", "completed");
        paymentOutput.put("amount", 100);

        context = ExpressionContext.builder()
                .inputs(Map.of(
                        "name", "test-payment",
                        "amount", 100,
                        "currency", "USD"
                ))
                .environment(Map.of(
                        "API_URL", "https://api.example.com",
                        "ENV", "staging"
                ))
                .stepOutput("create-payment", paymentOutput)
                .runMetadata(Map.of(
                        "runId", "run-001",
                        "seed", 42
                ))
                .secret("api-key", "sk-***4a2f")
                .build();
    }

    @Test
    void evaluateSimplePath() {
        JsonNode result = evaluator.evaluate("inputs.name", context);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("test-payment");
    }

    @Test
    void evaluateNestedPath() {
        JsonNode result = evaluator.evaluate("steps.create-payment.id", context);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("pay-123");
    }

    @Test
    void evaluateEnvironmentVariable() {
        JsonNode result = evaluator.evaluate("env.API_URL", context);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("https://api.example.com");
    }

    @Test
    void evaluateRunMetadata() {
        JsonNode result = evaluator.evaluate("run.runId", context);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("run-001");
    }

    @Test
    void evaluateNumericValue() {
        JsonNode result = evaluator.evaluate("inputs.amount", context);
        assertThat(result).isNotNull();
        assertThat(result.asInt()).isEqualTo(100);
    }

    @Test
    void aMissingPathIsMissingRatherThanNull() {
        // The distinction every rule about unresolvable templates rests on: a
        // path that matches nothing is an author's mistake, and a path holding
        // null is a document saying null. One answer for both cannot tell them
        // apart, which is what this used to return.
        assertThat(evaluator.evaluate("inputs.nonexistent", context).isMissingNode()).isTrue();
        assertThat(evaluator.evaluate("inputs.nonexistent.deep", context).isMissingNode())
                .isTrue();
    }

    @Test
    void aValueThatIsNullIsANullNode() {
        ObjectNode tree = new ObjectMapper().createObjectNode();
        tree.putObject("body").putNull("reference");

        JsonNode resolved = evaluator.resolvePath("body.reference", tree);

        assertThat(resolved.isNull()).isTrue();
        assertThat(resolved.isMissingNode()).isFalse();
    }

    @Test
    void aPathThroughANullMatchesNothing() {
        ObjectNode tree = new ObjectMapper().createObjectNode();
        tree.putObject("body").putNull("customer");

        assertThat(evaluator.resolvePath("body.customer.name", tree).isMissingNode()).isTrue();
    }

    @Test
    void anAbsentExpressionIsMissing() {
        assertThat(evaluator.evaluate(null, context).isMissingNode()).isTrue();
        assertThat(evaluator.evaluate("", context).isMissingNode()).isTrue();
        assertThat(evaluator.evaluate("  ", context).isMissingNode()).isTrue();
    }

    @Test
    void aPathIndexesAList() {
        // Why the first observed message had to be bound beside the list: a
        // path could not reach into one.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tree = mapper.createObjectNode();
        var messages = tree.putObject("steps").putObject("read").putArray("messages");
        messages.addObject().putObject("payload").put("paymentId", "pay-1");
        messages.addObject().putObject("payload").put("paymentId", "pay-2");

        assertThat(evaluator.resolvePath("steps.read.messages.1.payload.paymentId", tree)
                .asText()).isEqualTo("pay-2");
        assertThat(evaluator.resolvePath("steps.read.messages.9", tree).isMissingNode())
                .isTrue();
    }

    @Test
    void aQuotedSegmentIsAlwaysAKey() {
        // An object's key is a name and a list's index is a place. A document
        // with a "0" field means the field, and a quoted segment never indexes.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tree = mapper.createObjectNode();
        tree.putObject("byPosition").put("0", "a key that looks like an index");
        tree.putArray("byIndex").add("first");

        assertThat(evaluator.resolvePath("byPosition.0", tree).asText())
                .isEqualTo("a key that looks like an index");
        assertThat(evaluator.resolvePath("byIndex.\"0\"", tree).isMissingNode()).isTrue();
    }

    @Test
    void evaluateInvalidJmespathThrowsException() {
        assertThatThrownBy(() -> evaluator.evaluate("length(invalid!!!)", context))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    // Template resolution tests

    @Test
    void resolveSingleExpressionPreservesType() {
        Object result = evaluator.resolveTemplate("{{inputs.amount}}", context);
        assertThat(result).isEqualTo(100);
    }

    @Test
    void resolveSingleExpressionPreservesStringType() {
        Object result = evaluator.resolveTemplate("{{inputs.name}}", context);
        assertThat(result).isEqualTo("test-payment");
    }

    @Test
    void resolveStringInterpolation() {
        Object result = evaluator.resolveTemplate("Payment {{inputs.name}} is {{inputs.amount}}", context);
        assertThat(result).isEqualTo("Payment test-payment is 100");
    }

    @Test
    void resolveTemplateWithMissingValue() {
        Object result = evaluator.resolveTemplate("Value: {{inputs.nonexistent}}", context);
        assertThat(result).isEqualTo("Value: ");
    }

    @Test
    void resolveNullTemplateReturnsNull() {
        assertThat(evaluator.resolveTemplate(null, context)).isNull();
    }

    @Test
    void resolveEmptyTemplateReturnsEmpty() {
        assertThat(evaluator.resolveTemplate("", context)).isEqualTo("");
    }

    @Test
    void resolvePlainStringWithoutExpressions() {
        Object result = evaluator.resolveTemplate("no expressions here", context);
        assertThat(result).isEqualTo("no expressions here");
    }

    @Test
    void resolveStepOutputInTemplate() {
        Object result = evaluator.resolveTemplate(
                "Payment ID: {{steps.create-payment.id}}", context);
        assertThat(result).isEqualTo("Payment ID: pay-123");
    }

    @Test
    void resolveTemplateWithHyphenatedInput() {
        ExpressionContext ctx = ExpressionContext.builder()
                .inputs(Map.of("idempotency-key", "abc-123"))
                .build();
        Object result = evaluator.resolveTemplate("{{inputs.idempotency-key}}", ctx);
        assertThat(result).isEqualTo("abc-123");
    }

    // Input resolution tests

    @Test
    void resolveInputsMap() {
        Map<String, Object> inputs = Map.of(
                "name", "test-{{inputs.name}}",
                "amount", 100,
                "url", "{{env.API_URL}}/payments"
        );

        Map<String, Object> resolved = evaluator.resolveInputs(inputs, context);

        assertThat(resolved.get("name")).isEqualTo("test-test-payment");
        assertThat(resolved.get("amount")).isEqualTo(100);
        assertThat(resolved.get("url")).isEqualTo("https://api.example.com/payments");
    }

    @Test
    void resolveInputsHandlesNull() {
        assertThat(evaluator.resolveInputs(null, context)).isEmpty();
    }

    // Secret redaction tests

    @Test
    void secretContextIsDetected() {
        assertThat(context.isSecret("secrets.api-key")).isTrue();
        assertThat(context.isSecret("secrets.api-key.detail")).isTrue();
        assertThat(context.isSecret("inputs.name")).isFalse();
    }

    @Test
    void diagnosticStringRedactsSecrets() {
        String result = evaluator.toDiagnosticString(
                "secrets.api-key", "sk-***4a2f", context);
        assertThat(result).isEqualTo("[REDACTED]");
    }

    @Test
    void diagnosticStringShowsNonSecretValues() {
        String result = evaluator.toDiagnosticString(
                "inputs.name", "test-payment", context);
        assertThat(result).isEqualTo("test-payment");
    }

    @Test
    void diagnosticStringHandlesNull() {
        String result = evaluator.toDiagnosticString(
                "inputs.missing", null, context);
        assertThat(result).isEqualTo("null");
    }

    // JMESPath function tests (delegated to JMESPath when parentheses present)

    @Test
    void jmesPathTypeFunction() {
        JsonNode result = evaluator.evaluate("type(inputs.name)", context);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("string");
    }

    @Test
    void aParenthesisInsideANameIsNotAFunctionCall() {
        // The old rule was "contains a parenthesis", which turned a correct
        // path into a JMESPath expression and then reported a syntax error.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode headers = mapper.createObjectNode();
        headers.put("x-trace(id)", "abc");
        ExpressionContext weird = ExpressionContext.builder()
                .stepOutput("read", headers).build();

        assertThat(evaluator.evaluate("steps.read.\"x-trace(id)\"", weird).asText())
                .isEqualTo("abc");
    }

    @Test
    void aFunctionOverAHyphenatedNameSaysHowToWriteIt() {
        // JMESPath has no hyphen in an identifier and every example scenario
        // has one in a step name. The parser's answer is a character position;
        // this one is the fix.
        assertThatThrownBy(() -> evaluator.evaluate("type(steps.create-payment.id)", context))
                .isInstanceOf(ExpressionEvaluationException.class)
                .hasMessageContaining("only in quotes");

        assertThat(evaluator.evaluate("type(steps.\"create-payment\".id)", context).asText())
                .isEqualTo("string");
    }

    @Test
    void jmesPathLengthFunction() {
        JsonNode result = evaluator.evaluate("length(inputs.name)", context);
        assertThat(result).isNotNull();
        assertThat(result.asInt()).isEqualTo(12); // "test-payment"
    }

    // Determinism test

    @Test
    void evaluationIsDeterministic() {
        String expr = "steps.create-payment.id";
        String result1 = evaluator.evaluate(expr, context).asText();
        String result2 = evaluator.evaluate(expr, context).asText();
        assertThat(result1).isEqualTo(result2);
    }

    // Path resolver tests

    @Test
    void resolvePathHandlesQuotedSegments() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode steps = mapper.createObjectNode();
        ObjectNode myStep = mapper.createObjectNode();
        myStep.put("result", "ok");
        steps.set("my-step", myStep);
        root.set("steps", steps);

        JsonNode result = evaluator.resolvePath("steps.\"my-step\".result", root);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("ok");
    }

    @Test
    void resolvePathHandlesPlainHyphens() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode steps = mapper.createObjectNode();
        ObjectNode createPayment = mapper.createObjectNode();
        createPayment.put("id", "pay-456");
        steps.set("create-payment", createPayment);
        root.set("steps", steps);

        JsonNode result = evaluator.resolvePath("steps.create-payment.id", root);
        assertThat(result).isNotNull();
        assertThat(result.asText()).isEqualTo("pay-456");
    }
}
