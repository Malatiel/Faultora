package dev.faultora.extension.process;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.model.events.RunEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.faultora.extension.process.ExtensionProcess.fields;
import static dev.faultora.extension.process.ExtensionProcess.require;

/** Data-only v1 report exchange over the bounded extension frame. */
final class RenderWire {
    private static final String MALFORMED = "MALFORMED_MESSAGE";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(64).build())
                    .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(64).build())
                    .build())
            .addMixIn(RunEvent.class, VisibleEventType.class)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    // Keep the discriminator available to the record creator after subtype selection.
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType",
            include = JsonTypeInfo.As.EXISTING_PROPERTY, visible = true)
    private interface VisibleEventType { }

    private RenderWire() { }

    static ObjectNode input(List<RunEvent> events, int frameLimit) throws IOException {
        require(events != null, MALFORMED);
        var bytes = new ByteArrayOutputStream();
        // Bound serialization before constructing a tree from the full event list.
        OutputStream limited = new OutputStream() {
            @Override public void write(int value) throws IOException {
                require(bytes.size() < frameLimit, "MESSAGE_TOO_LARGE");
                bytes.write(value);
            }

            @Override public void write(byte[] values, int offset, int count) throws IOException {
                require(count <= frameLimit - bytes.size(), "MESSAGE_TOO_LARGE");
                bytes.write(values, offset, count);
            }
        };
        JSON.writeValue(limited, Map.of("events", events));
        return (ObjectNode) JSON.readTree(bytes.toByteArray());
    }

    static List<RunEvent> readInput(JsonNode input) throws IOException {
        fields(input, "events");
        var events = input.get("events");
        require(events.isArray(), MALFORMED);
        var decoded = new ArrayList<RunEvent>(events.size());
        for (JsonNode event : events) {
            require(event.isObject() && event.path("eventType").isTextual()
                    && event.path("timestamp").isIntegralNumber() && event.path("runId").isTextual(), MALFORMED);
            decoded.add(JSON.treeToValue(event, RunEvent.class));
        }
        return List.copyOf(decoded);
    }

    static ObjectNode result(String text) throws IOException {
        require(text != null && StandardCharsets.UTF_8.newEncoder().canEncode(text), MALFORMED);
        return JSON.createObjectNode().put("text", text);
    }

    static String readResult(JsonNode result) throws IOException {
        fields(result, "text");
        var value = result.get("text");
        require(value.isTextual() && StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue()), MALFORMED);
        return value.textValue();
    }
}
