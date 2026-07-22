package dev.faultora.spec;

import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
        assertThat(result.document().apiVersion()).isEqualTo("faultora.dev/v1alpha1");
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

    private String loadFixture(String name) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/spec/" + name)) {
            assertThat(is).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
