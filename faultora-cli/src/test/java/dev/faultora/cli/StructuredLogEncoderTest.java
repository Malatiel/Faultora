package dev.faultora.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLogEncoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "fixture-secret-0b8e41";

    @Test
    void anApprovedEventIsWrittenWithItsCorrelationAndNothingElse() throws Exception {
        LoggingEvent event = event("Fault rollback failed", null, Map.of("runId", "run-1",
                "nodeId", "create-payment", "authorization", "Bearer " + SECRET));
        event.setThrowableProxy(new ThrowableProxy(
                new IllegalStateException("body was " + SECRET, new IOException("token " + SECRET))));

        String line = encode("json", event);
        JsonNode parsed = JSON.readTree(line);

        assertThat(line).doesNotContain(SECRET).endsWith(System.lineSeparator());
        assertThat(parsed.path("level").asText()).isEqualTo("WARN");
        assertThat(parsed.path("logger").asText()).isEqualTo("dev.faultora.test");
        assertThat(parsed.path("event").asText()).isEqualTo("Fault rollback failed");
        assertThat(parsed.path("runId").asText()).isEqualTo("run-1");
        assertThat(parsed.path("nodeId").asText()).isEqualTo("create-payment");
        assertThat(parsed.has("authorization")).isFalse();
        assertThat(parsed.path("failure").toString())
                .isEqualTo("[\"java.lang.IllegalStateException\",\"java.io.IOException\"]");
        assertThat(parsed.path("time").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "text"})
    void aMessageBuiltFromValuesIsWithheldWhateverItsShape(String format) {
        for (LoggingEvent event : new LoggingEvent[] {
                event("Preformatted input: " + SECRET, null, Map.of()),
                event("Request to {} failed", SECRET, Map.of()),
                event("Run started", SECRET, Map.of())}) {
            String line = encode(format, event);
            assertThat(line).doesNotContain(SECRET).contains(StructuredLogEncoder.WITHHELD);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "text"})
    void anEventWithNoMessageIsWrittenAsWithheldRatherThanLost(String format) {
        String line = encode(format, event(null, null, Map.of("runId", "run-1")));
        assertThat(line).contains(StructuredLogEncoder.WITHHELD, "run-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "text"})
    void aLibrarysConfigurationDumpDoesNotReachTheLog(String format) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(format);
        encoder.start();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(captured);
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.INFO);
        try {
            // The real Kafka client's own INFO dump of every configured value.
            new ProducerConfig(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9",
                    ProducerConfig.CLIENT_ID_CONFIG, SECRET,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
            appender.stop();
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).as("the dump was logged and reached the encoder")
                .contains("org.apache.kafka.clients.producer.ProducerConfig", StructuredLogEncoder.WITHHELD);
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void textLineIsForAPersonAndKeepsTheSameRedaction() {
        LoggingEvent event = event("Node finished", null, Map.of("runId", "run-1", "password", SECRET));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException(SECRET)));

        String line = encode("text", event);

        assertThat(line).doesNotContain(SECRET)
                .contains("WARN ", "dev.faultora.test - Node finished", "runId=run-1",
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

        String line = new String(encoder.encode(event("Run started", null, Map.of())), StandardCharsets.UTF_8);

        assertThat(line).contains(" - Run started").doesNotStartWith("{");
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .anySatisfy(status -> assertThat(status.getMessage()).contains("must be text or json"));
    }

    @Test
    void aLineWithoutContextOrFailureHasNoEmptyFields() throws Exception {
        JsonNode parsed = JSON.readTree(encode("json", event("Run started", null, Map.of())));
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
