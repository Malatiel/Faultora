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

    /**
     * Whether a field of this type has a validation rule. A type without one
     * is refused rather than checked as an object, which would reject a
     * valid list or number; RunEventReaderTest holds every event to this.
     */
    static boolean supports(Class<?> type) {
        if (textual(type) || type == long.class || type == int.class || type == boolean.class
                || Map.class.isAssignableFrom(type)) {
            return true;
        }
        return type.isRecord() && Arrays.stream(type.getRecordComponents())
                .allMatch(component -> supports(component.getType()));
    }

    private static boolean textual(Class<?> type) {
        return type == String.class || type.isEnum()
                || dev.faultora.model.identifier.TypedId.class.isAssignableFrom(type);
    }

    private static void validateType(Class<?> type, com.fasterxml.jackson.databind.JsonNode value)
            throws IOException {
        if (!supports(type)) {
            throw new IOException("Unsupported journal field type");
        }
        if (value.isNull() && !type.isPrimitive()) {
            return;
        }
        boolean valid;
        if (textual(type)) {
            valid = value.isTextual();
        } else if (type == long.class || type == int.class) {
            valid = value.isIntegralNumber();
        } else if (type == boolean.class) {
            valid = value.isBoolean();
        } else {
            // A map, or a record whose components all have rules.
            valid = value.isObject();
        }
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
