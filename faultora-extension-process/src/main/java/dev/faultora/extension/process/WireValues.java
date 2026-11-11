package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.JsonFrameCodec;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.faultora.extension.process.ExtensionProcess.require;

/** Bounded, data-only maps shared by provider payloads. */
final class WireValues {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MALFORMED = "MALFORMED_MESSAGE";

    private WireValues() { }

    /** Limits tree construction before the frame codec checks the exact encoded size. */
    static final class InputBudget {
        private long remaining = JsonFrameCodec.MAX_FRAME_BYTES;

        void take(long minimumBytes) throws IOException {
            require(minimumBytes <= remaining, "MESSAGE_TOO_LARGE");
            remaining -= minimumBytes;
        }
    }

    static ObjectNode encodeMap(Map<?, ?> value, InputBudget budget) throws IOException {
        require(value != null, MALFORMED);
        return (ObjectNode) encodeData(value, 0, budget);
    }

    private static JsonNode encodeData(Object value, int depth, InputBudget budget) throws IOException {
        require(depth < 64, MALFORMED);
        if (value == null) {
            budget.take(4);
            return JSON.nullNode();
        }
        if (value instanceof Map<?, ?> map) {
            budget.take(2L + Math.max(0, map.size() - 1));
            ObjectNode object = JSON.createObjectNode();
            for (var entry : map.entrySet()) {
                require(entry.getKey() instanceof String, MALFORMED);
                String key = (String) entry.getKey();
                budget.take(key.length() + 3L);
                object.set(key, encodeData(entry.getValue(), depth + 1, budget));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            budget.take(2L + Math.max(0, list.size() - 1));
            var array = JSON.createArrayNode();
            for (Object item : list) array.add(encodeData(item, depth + 1, budget));
            return array;
        }
        Class<?> type = value.getClass();
        require(type == String.class || type == Boolean.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
                || type == BigInteger.class || type == BigDecimal.class, MALFORMED);
        require(!(value instanceof Float floating) || Float.isFinite(floating), MALFORMED);
        require(!(value instanceof Double floating) || Double.isFinite(floating), MALFORMED);
        budget.take(value instanceof String text ? text.length() + 2L : 1);
        return JSON.valueToTree(value);
    }

    static Map<String, Object> readMap(JsonNode object) throws IOException {
        require(object.isObject(), MALFORMED);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : object.properties()) values.put(entry.getKey(), readData(entry.getValue()));
        return Collections.unmodifiableMap(values);
    }

    private static Object readData(JsonNode value) throws IOException {
        if (value.isObject()) return readMap(value);
        if (value.isArray()) {
            var items = new ArrayList<>();
            for (JsonNode item : value) items.add(readData(item));
            return Collections.unmodifiableList(items);
        }
        if (value.isNull()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        throw new IOException(MALFORMED);
    }
}
