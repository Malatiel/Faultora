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
    void evaluateMissingPathReturnsNull() {
        JsonNode result = evaluator.evaluate("inputs.nonexistent", context);
        assertThat(result).isNull();
    }

    @Test
    void evaluateDeepMissingPathReturnsNull() {
        JsonNode result = evaluator.evaluate("inputs.nonexistent.deep", context);
        assertThat(result).isNull();
    }

    @Test
    void evaluateNullExpressionReturnsNull() {
        assertThat(evaluator.evaluate(null, context)).isNull();
        assertThat(evaluator.evaluate("", context)).isNull();
        assertThat(evaluator.evaluate("  ", context)).isNull();
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
