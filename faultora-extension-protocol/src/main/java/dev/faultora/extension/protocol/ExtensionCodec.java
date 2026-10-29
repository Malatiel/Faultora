package dev.faultora.extension.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import static dev.faultora.extension.protocol.JsonFrameCodec.check;
import static dev.faultora.extension.protocol.ProtocolException.Code.*;

/**
 * Version 1 envelope codec. Payload DTOs and handshake/session state are not
 * interpreted here. The caller must discard its channel on every refusal.
 */
public final class ExtensionCodec {
    private static final Set<String> FIELDS = Set.of("protocol", "kind", "version", "sessionId", "id", "data");
    private static final Set<String> KINDS = Set.of("hello", "ready", "request", "result", "error", "shutdown", "stopped");
    private final JsonFrameCodec frames;

    public ExtensionCodec(int maximumFrameBytes) {
        frames = new JsonFrameCodec(maximumFrameBytes);
    }

    public JsonNode read(InputStream input) throws IOException {
        JsonNode message = frames.read(input);
        if (message != null) validate(message);
        return message;
    }

    public void write(OutputStream output, JsonNode message) throws IOException {
        validate(message);
        frames.write(output, message);
    }

    /** A missing or uncorrelated reply cannot complete a pending call. */
    public JsonNode readReply(InputStream input, String sessionId, String id) throws IOException {
        check(sessionId != null && sessionId.matches("[0-9a-f]{32}") && validId(id), MALFORMED_MESSAGE);
        JsonNode message = read(input);
        check(message != null, PEER_CLOSED);
        check(Set.of("ready", "result", "error", "stopped").contains(message.get("kind").asText()), MALFORMED_MESSAGE);
        check(message.get("sessionId").isTextual() && sessionId.equals(message.get("sessionId").asText()), SESSION_MISMATCH);
        check(message.get("id").isTextual() && id.equals(message.get("id").asText()), ID_MISMATCH);
        return message;
    }

    private static void validate(JsonNode message) throws ProtocolException {
        check(message != null && message.isObject(), MALFORMED_MESSAGE);
        Set<String> fields = new HashSet<>();
        message.fieldNames().forEachRemaining(fields::add);
        check(fields.equals(FIELDS) && message.get("data").isObject(), MALFORMED_MESSAGE);
        check(message.get("protocol").isTextual() && message.get("protocol").asText().equals("faultora.extension"), MALFORMED_MESSAGE);
        String kind = message.get("kind").asText();
        check(message.get("kind").isTextual() && KINDS.contains(kind), MALFORMED_MESSAGE);
        JsonNode version = message.get("version");
        check(kind.equals("hello") ? version.isNull() : (kind.equals("error") && version.isNull())
                || (version.isTextual() && version.asText().equals("1.0")), INCOMPATIBLE_VERSION);
        JsonNode session = message.get("sessionId");
        check((kind.equals("error") && session.isNull())
                || (session.isTextual() && session.asText().matches("[0-9a-f]{32}")), MALFORMED_MESSAGE);
        JsonNode id = message.get("id");
        check((kind.equals("error") && id.isNull()) || (id.isTextual() && validId(id.asText())), ID_MISMATCH);
        if (!kind.equals("error")) {
            check((kind.equals("hello") || kind.equals("ready")) == id.asText().equals("0"), ID_MISMATCH);
        }
    }

    private static boolean validId(String id) {
        if (id == null || !id.matches("0|[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(id); return true; }
        catch (NumberFormatException invalid) { return false; }
    }
}
