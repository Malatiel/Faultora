package dev.faultora.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLogEncoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "fixture-secret-0b8e41";

    @Test
    void jsonLineCarriesTheCallSiteMessageAndCorrelationAndNothingElse() throws Exception {
        LoggingEvent event = event("Request to {} failed", SECRET, Map.of("runId", "run-1",
                "nodeId", "create-payment", "authorization", "Bearer " + SECRET));
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new IllegalStateException("body was " + SECRET, new IOException("token " + SECRET))));

        String line = encode("json", event);
        JsonNode parsed = JSON.readTree(line);

        assertThat(line).doesNotContain(SECRET).endsWith(System.lineSeparator());
        assertThat(parsed.path("level").asText()).isEqualTo("WARN");
        assertThat(parsed.path("logger").asText()).isEqualTo("dev.faultora.test");
        assertThat(parsed.path("event").asText()).isEqualTo("Request to {} failed");
        assertThat(parsed.path("runId").asText()).isEqualTo("run-1");
        assertThat(parsed.path("nodeId").asText()).isEqualTo("create-payment");
        assertThat(parsed.has("authorization")).isFalse();
        assertThat(parsed.path("failure").toString())
                .isEqualTo("[\"java.lang.IllegalStateException\",\"java.io.IOException\"]");
        assertThat(parsed.path("time").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void textLineIsForAPersonAndKeepsTheSameRedaction() {
        LoggingEvent event = event("Request to {} failed", SECRET, Map.of("runId", "run-1", "password", SECRET));
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(new IllegalStateException(SECRET)));

        String line = encode("text", event);

        assertThat(line).doesNotContain(SECRET)
                .contains("WARN ", "dev.faultora.test - Request to {} failed", "runId=run-1",
                        "(java.lang.IllegalStateException)")
                .doesNotContain("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yaml", "", "JSON"})
    void anUnknownFormatFallsBackToTextAndSaysSo(String format) {
        LoggerContext context = new LoggerContext();
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(format);
        encoder.start();

        String line = new String(encoder.encode(event("Plain", null, Map.of())), StandardCharsets.UTF_8);

        assertThat(line).contains(" - Plain").doesNotStartWith("{");
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anySatisfy(status -> assertThat(status.getMessage()).contains("must be text or json"));
    }

    @Test
    void aLineWithoutContextOrFailureHasNoEmptyFields() throws Exception {
        JsonNode parsed = JSON.readTree(encode("json", event("Plain", null, Map.of())));
        assertThat(parsed.fieldNames()).toIterable().containsExactly("time", "level", "logger", "event");
    }

    private static String encode(String format, LoggingEvent event) {
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(new LoggerContext());
        encoder.setFormat(format);
        encoder.start();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static LoggingEvent event(String message, Object argument, Map<String, String> logContext) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("dev.faultora.test");
        Object[] arguments = argument == null ? null : new Object[] {argument};
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.WARN, message, null, arguments);
        event.setMDCPropertyMap(logContext);
        return event;
    }
}
