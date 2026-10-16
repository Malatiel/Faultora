package dev.faultora.engine.journal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.faultora.model.events.RunEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Reads the version-one journal contract, including additive producer changes. */
public final class RunEventReader {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Map<String, Class<?>> TYPES = Arrays.stream(
            RunEvent.class.getAnnotation(JsonSubTypes.class).value())
            .collect(Collectors.toUnmodifiableMap(JsonSubTypes.Type::name, JsonSubTypes.Type::value));

    private RunEventReader() {
    }

    /** Unknown event types are skipped; malformed known events fail without echoing their data. */
    public static Optional<RunEvent> readLine(String line) throws IOException {
        try {
            var tree = MAPPER.readTree(line);
            if (tree == null || !tree.isObject()
                    || !tree.path("eventType").isTextual() || tree.path("eventType").asText().isBlank()
                    || !tree.path("timestamp").isIntegralNumber()
                    || !tree.path("runId").isTextual() || tree.path("runId").asText().isBlank()) {
                throw new IOException("Invalid journal event envelope");
            }
            Class<?> type = TYPES.get(tree.path("eventType").asText());
            if (type == null) {
                return Optional.empty();
            }
            for (var field : type.getRecordComponents()) {
                if (!tree.has(field.getName())) {
                    throw new IOException("Incomplete journal event");
                }
                validateType(field.getType(), tree.get(field.getName()));
            }
            return Optional.of(MAPPER.treeToValue(tree, RunEvent.class));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException malformed) {
            throw new IOException("Malformed journal event");
        }
    }

    private static void validateType(Class<?> type, com.fasterxml.jackson.databind.JsonNode value)
            throws IOException {
        if (value.isNull() && !type.isPrimitive()) {
            return;
        }
        boolean valid = type == String.class || type.isEnum()
                || dev.faultora.model.identifier.TypedId.class.isAssignableFrom(type)
                ? value.isTextual()
                : type == long.class || type == int.class ? value.isIntegralNumber()
                : type == boolean.class ? value.isBoolean() : value.isObject();
        if (!valid) {
            throw new IOException("Invalid journal field type");
        }
        if (type.isRecord()) {
            for (var field : type.getRecordComponents()) {
                if (!value.has(field.getName())) {
                    throw new IOException("Incomplete journal record");
                }
                validateType(field.getType(), value.get(field.getName()));
            }
        }
    }
}
