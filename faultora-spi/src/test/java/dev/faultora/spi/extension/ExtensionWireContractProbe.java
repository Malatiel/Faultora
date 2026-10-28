package dev.faultora.spi.extension;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.spi.result.ImportResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Executable specification only. Nothing in main sources loads or calls this probe. */
final class ExtensionWireContractProbe {
    static final int MAX_FRAME = 1048576;
    static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(64).build())
            .build()).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
    private static final Set<String> CODES = Set.of("INCOMPATIBLE_VERSION", "IDENTITY_MISMATCH",
            "UNSUPPORTED_PROVIDER", "LIMIT_MISMATCH", "SESSION_MISMATCH", "ID_MISMATCH", "INVALID_STATE",
            "MALFORMED_FRAME", "TRUNCATED_FRAME", "MESSAGE_TOO_LARGE", "MALFORMED_MESSAGE",
            "DEADLINE_EXCEEDED", "PROVIDER_ERROR", "PEER_CLOSED");

    enum Direction { PARENT, CHILD }
    private enum State { NEW, HELLO, IDLE, BUSY, STOPPING, CLOSED }
    private State state = State.NEW;
    private final long parentDeadline;
    private long deadline;
    private long nextId = 1;
    private String session;
    private String pendingId;
    private JsonNode plugin;
    private JsonNode pendingProvider;
    private Set<String> providers;
    private int offeredLimit;
    private int frameLimit = MAX_FRAME;
    private boolean failed;

    ExtensionWireContractProbe(long parentDeadline) {
        this.parentDeadline = parentDeadline;
    }

    static final class Refusal extends RuntimeException {
        Refusal(String code) { super(code); }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new Refusal(code);
    }

    static JsonNode readFrame(InputStream input, int limit) throws IOException {
        byte[] header = input.readNBytes(4);
        if (header.length == 0) return null;
        require(header.length == 4, "TRUNCATED_FRAME");
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        require(length != 0, "MALFORMED_FRAME");
        require(length <= limit, "MESSAGE_TOO_LARGE");
        byte[] body = input.readNBytes((int) length);
        require(body.length == length, "TRUNCATED_FRAME");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            require(!text.startsWith("\ufeff"), "MALFORMED_MESSAGE");
            // The lexical number bound includes a sign/exponent, not just digits.
            try (var tokens = JSON.createParser(text)) {
                while (tokens.nextToken() != null) {
                    require(!tokens.currentToken().isNumeric() || tokens.getText().length() <= 64, "MALFORMED_MESSAGE");
                }
            }
            JsonNode value = JSON.readTree(text);
            require(value != null && value.isObject(), "MALFORMED_MESSAGE");
            return value;
        } catch (IOException invalid) {
            throw new Refusal("MALFORMED_MESSAGE");
        }
    }

    static byte[] frame(JsonNode value, int limit) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        require(body.length <= limit, "MESSAGE_TOO_LARGE");
        return ByteBuffer.allocate(body.length + 4).putInt(body.length).put(body).array();
    }

    int frameLimit() { return frameLimit; }
    boolean failed() { return failed; }

    void receive(Direction direction, InputStream input, long now) throws IOException {
        try {
            JsonNode message = readFrame(input, frameLimit);
            if (message == null) eof();
            else accept(direction, message, now);
        } catch (Refusal invalid) {
            state = State.CLOSED;
            failed = true;
            throw invalid;
        } catch (IOException failedStream) {
            state = State.CLOSED;
            failed = true;
            throw new Refusal("PEER_CLOSED");
        }
    }

    void accept(Direction direction, JsonNode message, long now) {
        try {
            require(state != State.CLOSED, "INVALID_STATE");
            require(now < parentDeadline && (state == State.NEW || state == State.IDLE || now < deadline),
                    "DEADLINE_EXCEEDED");
            fields(message, "protocol", "kind", "version", "sessionId", "id", "data");
            require(text(message.get("protocol")).equals("faultora.extension"), "MALFORMED_MESSAGE");
            String kind = text(message.get("kind"));
            JsonNode data = message.get("data");
            require(data.isObject(), "MALFORMED_MESSAGE");
            if (kind.equals("error")) {
                fields(data, "code");
                require(CODES.contains(text(data.get("code"))), "MALFORMED_MESSAGE");
                boolean negotiated = state == State.IDLE || state == State.BUSY || state == State.STOPPING;
                require(negotiated ? text(message.get("version")).equals("1.0")
                        : message.get("version").isNull(), "INCOMPATIBLE_VERSION");
                if (!message.get("sessionId").isNull()) {
                    require(text(message.get("sessionId")).matches("[0-9a-f]{32}"), "MALFORMED_MESSAGE");
                    require(session == null || session.equals(message.get("sessionId").asText()), "SESSION_MISMATCH");
                }
                if (!message.get("id").isNull()) {
                    String errorId = id(message.get("id"));
                    require(pendingId == null || pendingId.equals(errorId), "ID_MISMATCH");
                }
                state = State.CLOSED;
                failed = true;
                return;
            }
            String id = id(message.get("id"));
            String nonce = text(message.get("sessionId"));
            require(nonce.matches("[0-9a-f]{32}"), "MALFORMED_MESSAGE");
            require(session == null || session.equals(nonce), "SESSION_MISMATCH");
            if (!kind.equals("hello") && !kind.equals("ready")) {
                require(text(message.get("version")).equals("1.0"), "INCOMPATIBLE_VERSION");
            }
            switch (kind) {
                case "hello" -> {
                    require(direction == Direction.PARENT && state == State.NEW, "INVALID_STATE");
                    require(message.get("version").isNull(), "INCOMPATIBLE_VERSION");
                    require(id.equals("0"), "ID_MISMATCH");
                    fields(data, "versions", "maxFrameBytes", "plugin", "providers");
                    require(data.get("versions").isArray() && !data.get("versions").isEmpty()
                            && data.get("versions").size() <= 8, "MALFORMED_MESSAGE");
                    Set<String> versions = new HashSet<>();
                    for (JsonNode version : data.get("versions")) {
                        require(versions.add(text(version)), "MALFORMED_MESSAGE");
                    }
                    require(versions.contains("1.0"), "INCOMPATIBLE_VERSION");
                    offeredLimit = integer(data.get("maxFrameBytes"), 1024, MAX_FRAME);
                    plugin(data.get("plugin"));
                    plugin = data.get("plugin").deepCopy();
                    providers = selectors(data.get("providers"));
                    session = nonce;
                    pendingId = id;
                    deadline = Math.min(now + 5000, parentDeadline);
                    state = State.HELLO;
                }
                case "ready" -> {
                    require(direction == Direction.CHILD && state == State.HELLO, "INVALID_STATE");
                    require(text(message.get("version")).equals("1.0"), "INCOMPATIBLE_VERSION");
                    require(id.equals(pendingId), "ID_MISMATCH");
                    fields(data, "maxFrameBytes", "plugin", "providers");
                    require(plugin.equals(data.get("plugin")), "IDENTITY_MISMATCH");
                    require(providers.equals(selectors(data.get("providers"))), "UNSUPPORTED_PROVIDER");
                    frameLimit = integer(data.get("maxFrameBytes"), 1024, MAX_FRAME);
                    require(frameLimit <= offeredLimit, "LIMIT_MISMATCH");
                    pendingId = null;
                    state = State.IDLE;
                }
                case "request", "shutdown" -> {
                    require(direction == Direction.PARENT && state == State.IDLE, "INVALID_STATE");
                    require(id.equals(Long.toString(nextId)) && nextId > 0, "ID_MISMATCH");
                    fields(data, kind.equals("request")
                            ? new String[]{"provider", "timeoutMs", "input"} : new String[]{"timeoutMs"});
                    int budget = integer(data.get("timeoutMs"), 1, 300000);
                    require(budget <= parentDeadline - now, "DEADLINE_EXCEEDED");
                    if (kind.equals("request")) {
                        require(providers.contains(selector(data.get("provider"))), "UNSUPPORTED_PROVIDER");
                        pendingProvider = data.get("provider").deepCopy();
                        input(pendingProvider.path("kind").asText(), data.get("input"));
                    }
                    pendingId = id;
                    nextId++;
                    deadline = now + budget;
                    state = kind.equals("request") ? State.BUSY : State.STOPPING;
                }
                case "result" -> {
                    require(direction == Direction.CHILD && state == State.BUSY, "INVALID_STATE");
                    require(id.equals(pendingId), "ID_MISMATCH");
                    fields(data, "provider", "value");
                    require(pendingProvider.equals(data.get("provider")), "ID_MISMATCH");
                    result(pendingProvider.path("kind").asText(), data.get("value"));
                    pendingId = null;
                    state = State.IDLE;
                }
                case "stopped" -> {
                    require(direction == Direction.CHILD && state == State.STOPPING, "INVALID_STATE");
                    require(id.equals(pendingId), "ID_MISMATCH");
                    fields(data);
                    state = State.CLOSED;
                }
                default -> throw new Refusal("MALFORMED_MESSAGE");
            }
        } catch (Refusal invalid) {
            state = State.CLOSED;
            failed = true;
            throw invalid;
        }
    }

    void eof() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            failed = true;
            throw new Refusal("PEER_CLOSED");
        }
    }

    private void input(String kind, JsonNode input) {
        switch (kind) {
            case "assertion" -> {
                fields(input, "params", "evidence", "context");
                require(input.get("params").isObject(), "MALFORMED_MESSAGE");
                JsonNode evidence = input.get("evidence");
                fields(evidence, "statusCode", "responseHeaders", "responseBody", "bodyComplete",
                        "durationMs", "error", "protocolEvidence");
                require(evidence.get("bodyComplete").isBoolean() && evidence.get("durationMs").isIntegralNumber()
                        && evidence.get("durationMs").canConvertToLong() && evidence.get("durationMs").asLong() >= 0
                        && evidence.get("responseHeaders").isObject() && evidence.get("protocolEvidence").isObject(),
                        "MALFORMED_MESSAGE");
                require(evidence.get("statusCode").isNull() || (evidence.get("statusCode").isIntegralNumber()
                        && evidence.get("statusCode").canConvertToInt()), "MALFORMED_MESSAGE");
                for (JsonNode values : evidence.get("responseHeaders")) {
                    require(values.isArray(), "MALFORMED_MESSAGE");
                    values.forEach(ExtensionWireContractProbe::text);
                }
                if (!evidence.get("responseBody").isNull()) {
                    try { Base64.getDecoder().decode(text(evidence.get("responseBody"))); }
                    catch (IllegalArgumentException invalid) { throw new Refusal("MALFORMED_MESSAGE"); }
                }
                fields(input.get("context"), "nodeId", "config", "schema");
                require(input.path("context").path("config").isObject()
                        && (input.path("context").path("schema").isNull()
                        || input.path("context").path("schema").isObject()), "MALFORMED_MESSAGE");
                text(input.path("context").get("nodeId"));
                dto(evidence, EvidenceSnapshot.class);
                dto(input.get("context"), AssertionContext.class);
            }
            case "importer" -> {
                fields(input, "sourceContent", "context");
                String source = text(input.get("sourceContent"));
                JsonNode context = input.get("context");
                fields(context, "sourceType", "maxRefDepth", "maxDocSizeBytes", "config");
                require(text(context.get("sourceType")).equals(pendingProvider.path("name").asText())
                        && context.get("config").isObject(), "MALFORMED_MESSAGE");
                integer(context.get("maxRefDepth"), 0, 64);
                int maximum = integer(context.get("maxDocSizeBytes"), 1, frameLimit);
                require(source.getBytes(StandardCharsets.UTF_8).length <= maximum, "MESSAGE_TOO_LARGE");
            }
            case "renderer" -> {
                fields(input, "events");
                require(input.get("events").isArray(), "MALFORMED_MESSAGE");
                input.get("events").forEach(event -> dto(event, RunEvent.class));
            }
            default -> throw new Refusal("UNSUPPORTED_PROVIDER");
        }
    }

    private static void result(String kind, JsonNode value) {
        switch (kind) {
            case "assertion" -> {
                fields(value, "outcome", "message", "details");
                require(Set.of("PASS", "FAIL", "INDETERMINATE").contains(text(value.get("outcome")))
                        && value.get("details").isObject(), "MALFORMED_MESSAGE");
                text(value.get("message"));
                dto(value, AssertionResult.class);
            }
            case "importer" -> {
                fields(value, "catalog", "errors", "warnings", "metadata");
                require(value.get("errors").isArray() && value.get("warnings").isArray()
                        && value.get("metadata").isObject(), "MALFORMED_MESSAGE");
                require(value.get("catalog").isNull() != value.get("errors").isEmpty(), "MALFORMED_MESSAGE");
                value.get("warnings").forEach(ExtensionWireContractProbe::text);
                dto(value, ImportResult.class);
            }
            case "renderer" -> { fields(value, "text"); text(value.get("text")); }
            default -> throw new Refusal("UNSUPPORTED_PROVIDER");
        }
    }

    private static void plugin(JsonNode value) {
        fields(value, "id", "version");
        name(value.get("id"));
        require(!text(value.get("version")).isBlank() && value.get("version").asText().length() <= 128,
                "MALFORMED_MESSAGE");
    }

    private static Set<String> selectors(JsonNode values) {
        require(values.isArray() && !values.isEmpty() && values.size() <= 32, "MALFORMED_MESSAGE");
        Set<String> selected = new HashSet<>();
        for (JsonNode value : values) require(selected.add(selector(value)), "MALFORMED_MESSAGE");
        return selected;
    }

    private static String selector(JsonNode value) {
        fields(value, "kind", "name");
        require(Set.of("assertion", "importer", "renderer").contains(text(value.get("kind"))), "UNSUPPORTED_PROVIDER");
        return value.get("kind").asText() + "/" + name(value.get("name"));
    }

    private static String name(JsonNode value) {
        String text = text(value);
        require(text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), "MALFORMED_MESSAGE");
        return text;
    }

    private static String text(JsonNode value) {
        require(value != null && value.isTextual(), "MALFORMED_MESSAGE");
        return value.asText();
    }

    private static String id(JsonNode value) {
        String id = text(value);
        require(id.matches("0|[1-9][0-9]{0,18}"), "MALFORMED_MESSAGE");
        try { Long.parseLong(id); }
        catch (NumberFormatException invalid) { throw new Refusal("ID_MISMATCH"); }
        return id;
    }

    private static int integer(JsonNode value, int minimum, int maximum) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= minimum && value.asInt() <= maximum, "MALFORMED_MESSAGE");
        return value.asInt();
    }

    private static void fields(JsonNode value, String... names) {
        require(value != null && value.isObject(), "MALFORMED_MESSAGE");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names)), "MALFORMED_MESSAGE");
    }

    private static <T> T dto(JsonNode value, Class<T> type) {
        try { return JSON.treeToValue(value, type); }
        catch (IOException | IllegalArgumentException invalid) { throw new Refusal("MALFORMED_MESSAGE"); }
    }
}
