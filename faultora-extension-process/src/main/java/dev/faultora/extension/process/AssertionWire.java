package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.evidence.EvidenceExport;
import dev.faultora.spi.extension.EvidenceSnapshot;
import dev.faultora.spi.result.AssertionResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static dev.faultora.extension.process.ExtensionProcess.fields;
import static dev.faultora.extension.process.ExtensionProcess.require;

/** The v1 assertion DTOs, with no reflective serialization of application values. */
final class AssertionWire {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MALFORMED = "MALFORMED_MESSAGE";

    record Call(Map<String, Object> params, EvidenceView evidence, AssertionContext context) { }

    /** Limits tree construction before the frame codec checks the exact encoded size. */
    private static final class InputBudget {
        private long remaining = JsonFrameCodec.MAX_FRAME_BYTES;

        void take(long minimumBytes) throws IOException {
            require(minimumBytes <= remaining, "MESSAGE_TOO_LARGE");
            remaining -= minimumBytes;
        }
    }

    private AssertionWire() { }

    static ObjectNode input(Map<String, Object> params, EvidenceView view, AssertionContext context)
            throws IOException {
        byte[] body = view.responseBody().orElse(null);
        require(view.durationMs() >= 0 && context.nodeId() != null, MALFORMED);
        var budget = new InputBudget();
        budget.take(context.nodeId().length() + 2L);
        if (body != null) budget.take(4L * ((body.length + 2L) / 3) + 2);

        ObjectNode input = JSON.createObjectNode();
        input.set("params", encodeMap(params, budget));
        ObjectNode evidence = input.putObject("evidence");
        evidence.put("statusCode", view.statusCode().orElse(null));
        ObjectNode headers = evidence.putObject("responseHeaders");
        Map<String, List<String>> responseHeaders = view.responseHeaders();
        require(responseHeaders != null, MALFORMED);
        for (var entry : responseHeaders.entrySet()) {
            String name = entry.getKey();
            require(name != null, MALFORMED);
            if (!EvidencePolicy.MINIMAL.headerDenylist().contains(name.toLowerCase(Locale.ROOT))
                    && view.headerReadable(name)) {
                var values = entry.getValue();
                require(values != null, MALFORMED);
                budget.take(name.length() + 5L + Math.max(0, values.size() - 1));
                var array = headers.putArray(name);
                for (String value : values) {
                    require(value != null, MALFORMED);
                    budget.take(value.length() + 2L);
                    array.add(value);
                }
            }
        }

        evidence.put("responseBody", body == null ? null : Base64.getEncoder().encodeToString(body));
        evidence.put("bodyComplete", body != null && view.bodyComplete());
        evidence.put("durationMs", view.durationMs());
        evidence.set("error", JSON.valueToTree(EvidenceExport.connectorError(view.error().orElse(null))));
        var protocolEvidence = view.protocolEvidence();
        require(protocolEvidence != null && protocolEvidence.values().stream().allMatch(Objects::nonNull), MALFORMED);
        evidence.set("protocolEvidence", encodeMap(protocolEvidence, budget));
        ObjectNode configuration = input.putObject("context").put("nodeId", context.nodeId());
        configuration.set("config", encodeMap(context.config(), budget));
        configuration.set("schema", context.schema() == null ? JSON.nullNode() : encodeMap(context.schema(), budget));
        return input;
    }

    static Call readInput(JsonNode input) throws IOException {
        fields(input, "params", "evidence", "context");
        JsonNode evidence = input.get("evidence");
        fields(evidence, "statusCode", "responseHeaders", "responseBody", "bodyComplete",
                "durationMs", "error", "protocolEvidence");
        JsonNode status = evidence.get("statusCode");
        JsonNode body = evidence.get("responseBody");
        JsonNode complete = evidence.get("bodyComplete");
        JsonNode duration = evidence.get("durationMs");
        require(status.isNull() || status.isIntegralNumber() && status.canConvertToInt(), MALFORMED);
        require(body.isNull() || body.isTextual(), MALFORMED);
        require(complete.isBoolean() && (!complete.booleanValue() || !body.isNull()), MALFORMED);
        require(duration.isIntegralNumber() && duration.canConvertToLong() && duration.longValue() >= 0, MALFORMED);
        var protocolEvidence = readMap(evidence.get("protocolEvidence"));
        require(protocolEvidence.values().stream().allMatch(Objects::nonNull), MALFORMED);
        var snapshot = new EvidenceSnapshot(status.isNull() ? null : status.intValue(),
                readHeaders(evidence.get("responseHeaders")), body.textValue(), complete.booleanValue(),
                duration.longValue(), readError(evidence.get("error")), protocolEvidence);
        EvidenceView view;
        try {
            view = snapshot.asView();
        } catch (IllegalArgumentException invalidBody) {
            throw new IOException(MALFORMED);
        }

        JsonNode context = input.get("context");
        fields(context, "nodeId", "config", "schema");
        require(context.get("nodeId").isTextual(), MALFORMED);
        JsonNode schema = context.get("schema");
        return new Call(readMap(input.get("params")), view, new AssertionContext(context.get("nodeId").textValue(),
                readMap(context.get("config")), schema.isNull() ? null : readMap(schema)));
    }

    static ObjectNode result(AssertionResult result) throws IOException {
        require(result != null && result.outcome() != null && result.message() != null && result.details() != null,
                "PROVIDER_ERROR");
        return JSON.valueToTree(EvidenceExport.assertion(result));
    }

    static AssertionResult readResult(JsonNode result) throws IOException {
        fields(result, "outcome", "message", "details");
        require(result.get("outcome").isTextual() && result.get("message").isTextual()
                && result.get("details").isObject(), MALFORMED);
        try {
            var outcome = AssertionResult.Outcome.valueOf(result.get("outcome").textValue());
            return EvidenceExport.assertion(new AssertionResult(outcome, "", Map.of()));
        } catch (IllegalArgumentException invalidOutcome) {
            throw new IOException(MALFORMED);
        }
    }

    private static Map<String, List<String>> readHeaders(JsonNode headers) throws IOException {
        require(headers.isObject(), MALFORMED);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var entry : headers.properties()) {
            var values = entry.getValue();
            require(values.isArray(), MALFORMED);
            var strings = new ArrayList<String>();
            for (JsonNode value : values) {
                require(value.isTextual(), MALFORMED);
                strings.add(value.textValue());
            }
            result.put(entry.getKey(), List.copyOf(strings));
        }
        return result;
    }

    private static NormalizedError readError(JsonNode error) throws IOException {
        if (error.isNull()) return null;
        fields(error, "category", "code", "message", "retryable", "metadata");
        require(error.get("category").isTextual() && error.get("code").isTextual()
                && error.get("message").isTextual() && error.get("retryable").isBoolean(), MALFORMED);
        try {
            return new NormalizedError(NormalizedError.ErrorCategory.valueOf(error.get("category").textValue()),
                    error.get("code").textValue(), error.get("message").textValue(),
                    error.get("retryable").booleanValue(), readMap(error.get("metadata")));
        } catch (IllegalArgumentException invalidCategory) {
            throw new IOException(MALFORMED);
        }
    }

    private static ObjectNode encodeMap(Map<?, ?> value, InputBudget budget) throws IOException {
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

    private static Map<String, Object> readMap(JsonNode object) throws IOException {
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
