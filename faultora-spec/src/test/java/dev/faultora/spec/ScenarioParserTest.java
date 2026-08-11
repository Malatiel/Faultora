package dev.faultora.spec;

import dev.faultora.spec.model.*;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioParserTest {

    private ScenarioParser parser;
    private ScenarioValidator validator;

    @BeforeEach
    void setUp() {
        parser = new ScenarioParser();
        validator = new ScenarioValidator();
    }

    @Test
    void parseValidScenario() throws IOException {
        String content = loadFixture("valid-scenario.yaml");
        ParseResult<ScenarioDocument> result = parser.parse(content);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document()).isNotNull();
        assertThat(result.document().apiVersion()).isEqualTo(ApiVersions.CURRENT);
        assertThat(result.document().kind()).isEqualTo("Scenario");
        assertThat(result.document().metadata().name()).isEqualTo("duplicate-payment");
        assertThat(result.document().execute()).hasSize(1);
        assertThat(result.document().assertions()).hasSize(2);
    }

    @Test
    void parseAndValidateValidScenario() throws IOException {
        String content = loadFixture("valid-scenario.yaml");
        ParseResult<ScenarioDocument> parseResult = parser.parse(content);
        assertThat(parseResult.isSuccess()).isTrue();

        ParseResult<ScenarioDocument> validationResult = validator.validate(parseResult.document());
        assertThat(validationResult.isSuccess()).isTrue();
        assertThat(validationResult.errors()).isEmpty();
    }

    @Test
    void parseInvalidScenarioReportsErrors() throws IOException {
        String content = loadFixture("invalid-scenario.yaml");
        ParseResult<ScenarioDocument> parseResult = parser.parse(content);
        assertThat(parseResult.isSuccess()).isTrue();

        ParseResult<ScenarioDocument> validationResult = validator.validate(parseResult.document());
        assertThat(validationResult.isSuccess()).isFalse();
        assertThat(validationResult.errors()).isNotEmpty();

        // Should detect duplicate step ID and missing dependsOn reference
        assertThat(validationResult.errors().stream().map(Diagnostic::message))
                .anyMatch(m -> m.contains("Duplicate step id"));
        assertThat(validationResult.errors().stream().map(Diagnostic::message))
                .anyMatch(m -> m.contains("nonexistent-step"));
    }

    @Test
    void parseWrongVersionReportsError() throws IOException {
        String content = loadFixture("wrong-version.yaml");
        ParseResult<ScenarioDocument> result = parser.parse(content);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().stream().map(Diagnostic::message))
                .anyMatch(m -> m.contains("Unsupported apiVersion"));
    }

    @Test
    void aDocumentWrittenAgainstThePreviewStillRuns() {
        // The release whose purpose is stability must not be the one that turns
        // every existing pipeline red. A deprecated version parses, succeeds,
        // and says what to do about it — as a warning, which no exit code reads.
        String preview = SMOKE_SCENARIO.formatted("faultora.dev/v1alpha1");

        ParseResult<ScenarioDocument> result = parser.parse(preview);

        assertThat(result.isSuccess())
                .as("a warning is not a failure").isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings().stream().map(Diagnostic::message))
                .anyMatch(message -> message.contains("faultora migrate")
                        && message.contains(ApiVersions.SUNSET));
    }

    @Test
    void theFrozenVersionParsesWithNothingToSay() {
        ParseResult<ScenarioDocument> result =
                parser.parse(SMOKE_SCENARIO.formatted(ApiVersions.CURRENT));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.diagnostics())
                .as("the current version is not deprecated and says nothing")
                .isEmpty();
    }

    @Test
    void aVersionNobodyReadsNamesTheOnesThatAreRead() {
        // What an operator does next is the point of the message.
        ParseResult<ScenarioDocument> result =
                parser.parse(SMOKE_SCENARIO.formatted("faultora.dev/v2"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors().stream().map(Diagnostic::message))
                .anyMatch(message -> message.contains(ApiVersions.CURRENT)
                        && message.contains("faultora.dev/v1alpha1"));
    }

    /** The smallest document that parses, with its version left to the caller. */
    private static final String SMOKE_SCENARIO = """
            apiVersion: %s
            kind: Scenario
            metadata:
              name: smallest-thing-that-parses
            execute:
              - id: pause
                type: wait
                timeout: 10ms
            """;

    @Test
    void parseEmptyContentReportsError() {
        ParseResult<ScenarioDocument> result = parser.parse("");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void parseNullContentReportsError() {
        ParseResult<ScenarioDocument> result = parser.parse(null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void validatorRejectsUnknownAssertionAndFaultReferences() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("references", "references", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "execute", "operation", "operation",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(new FaultStep(
                        "fault", "latency", "target", Map.of(), "1s",
                        List.of("missing-fault-dependency"), Map.of())),
                List.of(new AssertionStep(
                        "assert", "status", Map.of(), "missing-target",
                        List.of("missing-assertion-dependency"), null, Map.of())),
                List.of());

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains(
                        "References unknown step: missing-fault-dependency",
                        "References unknown step: missing-assertion-dependency",
                        "References unknown step: missing-target");
    }

    @Test
    void validatorRejectsUnsupportedTypeAndInvalidDuration() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("types", "types", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(
                        new ScenarioStep(
                                "script", "script", null,
                                Map.of(), null, List.of(), null, null, Map.of()),
                        new ScenarioStep(
                                "wait", "wait", null,
                                Map.of(), null, List.of(), "forever", null, Map.of())),
                List.of(), List.of(), List.of());

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains(
                        "Unsupported step type in this release: script",
                        "Wait step requires a positive duration");
    }

    @Test
    void validatorAcceptsParallelGroupAndRejectsInvalidChildren() {
        ScenarioDocument valid = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("parallel", "parallel", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep("race", "parallel", null,
                        Map.of(), null, List.of(), null, null, false,
                        List.of(
                                new ScenarioStep("first", "operation", "create-payment",
                                        Map.of(), null, List.of(), null, null, Map.of()),
                                new ScenarioStep("second", "operation", "create-payment",
                                        Map.of(), null, List.of(), null, null, Map.of())),
                        Map.of())),
                List.of(), List.of(), List.of());

        assertThat(validator.validate(valid).isSuccess()).isTrue();

        ScenarioDocument invalid = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("parallel", "parallel", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep("race", "parallel", null,
                        Map.of(), null, List.of(), null, null, false,
                        List.of(
                                new ScenarioStep("waiting", "wait", null,
                                        Map.of(), null, List.of(), "1s", null, Map.of()),
                                new ScenarioStep("dependent", "operation", "create-payment",
                                        Map.of(), null, List.of("waiting"), null, null, Map.of()),
                                new ScenarioStep("nested", "parallel", null,
                                        Map.of(), null, List.of(), null, null, false,
                                        List.of(new ScenarioStep("inner", "operation", "op",
                                                Map.of(), null, List.of(), null, null, Map.of())),
                                        Map.of())),
                        Map.of())),
                List.of(), List.of(), List.of());

        ParseResult<ScenarioDocument> result = validator.validate(invalid);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .anyMatch(m -> m.contains("Parallel children must be operation steps"))
                .anyMatch(m -> m.contains("cannot declare dependsOn"))
                .anyMatch(m -> m.contains("cannot be nested"));
    }

    @Test
    void validatorAcceptsCompleteFaultStep() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("faults", "faults", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "call", "operation", "operation",
                        Map.of(), null, List.of("inject"), null, null, Map.of())),
                List.of(new FaultStep(
                        "inject", "http-latency", "default",
                        Map.of("delayMs", 200), "2s", List.of(), Map.of())),
                List.of(), List.of());

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validatorRejectsFaultStepWithoutTypeOrDuration() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("faults", "faults", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "call", "operation", "operation",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(new FaultStep(
                        "inject", " ", "default", Map.of(), null, List.of(), Map.of())),
                List.of(), List.of());

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains(
                        "Fault step requires a faultType",
                        "Fault step requires a positive duration (e.g. 500ms, 5s)");
    }

    @Test
    void validatorRejectsExpectErrorCombinedWithRetry() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("retry", "retry", Map.of(), Map.of()),
                Map.of(),
                List.of(),
                List.of(new ScenarioStep(
                        "call", "operation", "operation",
                        Map.of(), null, List.of(), null,
                        new ScenarioStep.RetryPolicy(3, 10, 2, 100),
                        true, Map.of())),
                List.of(), List.of(), List.of());

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .anyMatch(message -> message.contains("expectError cannot be combined with retry"));
    }

    @Test
    void parserReadsFaultStepsAndExpectError() {
        String yaml = """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: fault-scenario
                execute:
                  - id: first-call
                    type: operation
                    operationId: create-payment
                    expectError: true
                    dependsOn: [inject-loss]
                faults:
                  - id: inject-loss
                    faultType: http-response-loss
                    targetScope: default
                    duration: 5s
                assertions: []
                """;

        ParseResult<ScenarioDocument> result = parser.parse(yaml);

        assertThat(result.isSuccess()).isTrue();
        ScenarioDocument document = result.document();
        assertThat(document.execute().get(0).expectError()).isTrue();
        assertThat(document.faults()).hasSize(1);
        assertThat(document.faults().get(0).faultType()).isEqualTo("http-response-loss");
        assertThat(document.faults().get(0).duration()).isEqualTo("5s");

        ParseResult<ScenarioDocument> validation = validator.validate(document);
        assertThat(validation.isSuccess()).isTrue();
    }

    private String loadFixture(String name) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/spec/" + name)) {
            assertThat(is).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesAndValidatesRepeatAndEventuallyGroups() {
        String content = """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                timeout: 2m
                metadata:
                  name: control-flow
                execute:
                  - id: batch
                    type: repeat
                    forEach: [EUR, USD]
                    steps:
                      - id: create
                        type: operation
                        operationId: create-payment
                        inputs:
                          body:
                            currency: "{{repeat.item}}"
                  - id: settled
                    type: eventually
                    timeout: 10s
                    interval: 500ms
                    dependsOn: [batch]
                    steps:
                      - id: poll
                        type: operation
                        operationId: get-payment
                    until:
                      - assertionType: jsonpath
                        params:
                          path: status
                          equals: settled
                        message: the payment settles asynchronously
                """;

        ParseResult<ScenarioDocument> parsed = parser.parse(content);
        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.document().timeout()).isEqualTo("2m");

        ScenarioStep repeat = parsed.document().execute().get(0);
        assertThat(repeat.forEach()).containsExactly("EUR", "USD");
        assertThat(repeat.steps()).hasSize(1);

        ScenarioStep eventually = parsed.document().execute().get(1);
        assertThat(eventually.interval()).isEqualTo("500ms");
        assertThat(eventually.until()).hasSize(1);
        assertThat(eventually.until().get(0).assertionType()).isEqualTo("jsonpath");

        assertThat(validator.validate(parsed.document()).isSuccess()).isTrue();
    }

    @Test
    void validatorRejectsRepeatWithoutAnIterationSource() {
        ScenarioStep repeat = new ScenarioStep(
                "batch", "repeat", null, null, null, List.of(), null, null, false,
                List.of(new ScenarioStep("create", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                null, null, null, null, Map.of());

        ParseResult<ScenarioDocument> result = validator.validate(documentWith(repeat));

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains("Repeat step requires exactly one of count or forEach");
    }

    @Test
    void validatorRejectsEventuallyWithoutConditions() {
        ScenarioStep eventually = new ScenarioStep(
                "settled", "eventually", null, null, null, List.of(), "10s", null, false,
                List.of(new ScenarioStep("poll", "operation", "get-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                null, null, null, null, Map.of());

        ParseResult<ScenarioDocument> result = validator.validate(documentWith(eventually));

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains("Eventually step requires at least one until condition");
    }

    @Test
    void validatorRejectsOperationFieldsOnAGroupStep() {
        ScenarioStep repeat = new ScenarioStep(
                "batch", "repeat", null, null, "bound", List.of(), null,
                new ScenarioStep.RetryPolicy(3, 100, 2, 1000), false,
                List.of(new ScenarioStep("create", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                2, null, null, null, Map.of());

        ParseResult<ScenarioDocument> result = validator.validate(documentWith(repeat));

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains(
                        "Retry belongs on the child steps of a repeat group",
                        "outputAs belongs on the child steps of a repeat group");
    }

    @Test
    void validatorRejectsAScenarioTimeoutThatIsNotADuration() {
        ScenarioDocument document = new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("deadline", "deadline", Map.of(), Map.of()),
                Map.of(), List.of(),
                List.of(new ScenarioStep("step", "operation", "create-payment",
                        Map.of(), null, List.of(), null, null, Map.of())),
                List.of(), List.of(), List.of(), "soon");

        ParseResult<ScenarioDocument> result = validator.validate(document);

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains("Scenario timeout must be a positive duration: soon");
    }

    @Test
    void parsesAndValidatesGeneratedRequestValues() {
        String content = """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: generated-request
                execute:
                  - id: create-payment
                    type: operation
                    operationId: create-payment
                    generate:
                      fields: [body]
                      strategy: boundary
                      preferExamples: false
                    inputs:
                      body:
                        currency: EUR
                """;

        ParseResult<ScenarioDocument> parsed = parser.parse(content);

        assertThat(parsed.isSuccess()).isTrue();
        ScenarioStep step = parsed.document().execute().get(0);
        assertThat(step.generate().fields()).containsExactly("body");
        assertThat(step.generate().strategy()).isEqualTo("boundary");
        assertThat(step.generate().preferExamples()).isFalse();
        assertThat(validator.validate(parsed.document()).isSuccess()).isTrue();
    }

    @Test
    void validatorRejectsAnUnknownGenerationStrategy() {
        ScenarioStep step = new ScenarioStep(
                "create", "operation", "create-payment", Map.of(), null, List.of(),
                null, null, false, null, null, null, null, null,
                new ScenarioStep.Generate(List.of("body"), "creative", null), Map.of());

        ParseResult<ScenarioDocument> result = validator.validate(documentWith(step));

        assertThat(result.errors()).extracting(Diagnostic::message)
                .anyMatch(message -> message.contains("Unknown generation strategy: creative"));
    }

    @Test
    void validatorRejectsGenerateOnAStepThatInvokesNoOperation() {
        ScenarioStep step = new ScenarioStep(
                "pause", "wait", null, Map.of(), null, List.of(),
                "100ms", null, false, null, null, null, null, null,
                new ScenarioStep.Generate(List.of("body"), null, null), Map.of());

        ParseResult<ScenarioDocument> result = validator.validate(documentWith(step));

        assertThat(result.errors()).extracting(Diagnostic::message)
                .contains("generate is only allowed on operation steps");
    }

    private ScenarioDocument documentWith(ScenarioStep step) {
        return new ScenarioDocument(
                "faultora.dev/v1alpha1", "Scenario",
                new ScenarioMetadata("control-flow", "control-flow", Map.of(), Map.of()),
                Map.of(), List.of(), List.of(step), List.of(), List.of(), List.of());
    }

}
