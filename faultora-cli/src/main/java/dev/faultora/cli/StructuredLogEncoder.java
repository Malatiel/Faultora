package dev.faultora.cli;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Log lines that say what happened and to which run, and never what was in it.
 * <p>
 * A message is written only when it is one of Faultora's own approved events,
 * fixed text with no arguments. Any other message, a library's included, is
 * replaced by {@value #WITHHELD}: a message can be assembled from values
 * before it reaches the logger — a configuration dump, a concatenated string
 * — so neither its text nor its arguments can be trusted not to carry a
 * request value or a credential. The level and logger name still say that
 * something happened and where. A failure carries the exception's class
 * names, not its messages, which quote whatever the failing code was holding.
 * Of the logging context only the run and node identifiers are kept.
 * <p>
 * {@code text} is for a person at a terminal and is the default; {@code json}
 * is one object per line for a collector, selected with
 * {@code FAULTORA_LOG_FORMAT=json}. Either way the lines go to standard error,
 * apart from the command's own output and reports.
 */
public final class StructuredLogEncoder extends EncoderBase<ILoggingEvent> {

    /** The context keys that identify a log line's run and node; nothing else is exported. */
    static final List<String> CORRELATION_KEYS = List.of("runId", "nodeId");

    /**
     * Every message Faultora's own code logs. A call site with any other text,
     * an argument or a concatenation fails LogEventInventoryTest.
     */
    public static final Set<String> APPROVED_EVENTS = Set.of(
            "Run started", "Run finished", "Node started", "Node finished",
            "Fault rollback failed", "Failed to close HTTP response",
            "Failed to flush run journal", "Failed to append run journal event",
            "Failed to close run journal");

    /** What stands in for a message that is not an approved event. */
    public static final String WITHHELD = "Message withheld";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] NONE = new byte[0];

    private String format = "text";
    private boolean json;

    /** Set from the logging configuration: {@code text} or {@code json}. */
    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    public void start() {
        String chosen = format == null ? "" : format.trim();
        if (!chosen.equals("text") && !chosen.equals("json")) {
            addError("FAULTORA_LOG_FORMAT must be text or json, not '" + chosen + "'; using text");
            chosen = "text";
        }
        json = chosen.equals("json");
        super.start();
    }

    @Override
    public byte[] headerBytes() {
        return NONE;
    }

    @Override
    public byte[] footerBytes() {
        return NONE;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        String line = json ? json(event) : text(event);
        return (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static String json(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("event", message(event));
        correlation(event).forEach(fields::put);
        List<String> failure = failure(event.getThrowableProxy());
        if (!failure.isEmpty()) {
            fields.put("failure", failure);
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException impossible) {
            return "{\"level\":\"ERROR\",\"event\":\"log line could not be encoded\"}";
        }
    }

    private static String text(ILoggingEvent event) {
        StringBuilder line = new StringBuilder()
                .append(Instant.ofEpochMilli(event.getTimeStamp())).append(' ')
                .append(String.format("%-5s", event.getLevel())).append(' ')
                .append(event.getLoggerName()).append(" - ").append(message(event));
        correlation(event).forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
        List<String> failure = failure(event.getThrowableProxy());
        if (!failure.isEmpty()) {
            line.append(" (").append(String.join(" <- ", failure)).append(')');
        }
        return line.toString();
    }

    private static String message(ILoggingEvent event) {
        Object[] arguments = event.getArgumentArray();
        boolean literal = arguments == null || arguments.length == 0;
        String message = event.getMessage();
        return literal && message != null && APPROVED_EVENTS.contains(message) ? message : WITHHELD;
    }

    private static Map<String, String> correlation(ILoggingEvent event) {
        Map<String, String> context = event.getMDCPropertyMap();
        Map<String, String> kept = new LinkedHashMap<>();
        for (String key : CORRELATION_KEYS) {
            String value = context == null ? null : context.get(key);
            if (value != null) {
                kept.put(key, value);
            }
        }
        return kept;
    }

    /** The exception and its causes, outermost first, as class names only. */
    private static List<String> failure(IThrowableProxy throwable) {
        List<String> classes = new ArrayList<>();
        for (IThrowableProxy current = throwable; current != null && classes.size() < 8;
                current = current.getCause()) {
            classes.add(current.getClassName());
        }
        return classes;
    }
}
