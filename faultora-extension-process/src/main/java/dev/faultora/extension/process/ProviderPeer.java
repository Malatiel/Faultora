package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.ExtensionCodec;
import dev.faultora.extension.protocol.JsonFrameCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

import static dev.faultora.extension.process.ExtensionProcess.fields;
import static dev.faultora.extension.process.ExtensionProcess.require;
import static dev.faultora.extension.process.ExtensionProcess.selector;

/** Trusted child endpoint for one admitted provider. */
final class ProviderPeer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MALFORMED = "MALFORMED_MESSAGE";

    @FunctionalInterface
    interface ProviderBinding {
        ProviderRequest decode(JsonNode input, int frameLimit) throws IOException;
    }

    @FunctionalInterface
    interface ProviderRequest {
        ObjectNode invoke() throws IOException;
    }

    @FunctionalInterface
    interface ProviderLoader {
        ProviderBinding load(String name) throws IOException;
    }

    private ProviderPeer() { }

    static void run(String kind, ProviderLoader loader, InputStream input, OutputStream output) throws IOException {
        var handshake = new ExtensionCodec(JsonFrameCodec.MAX_FRAME_BYTES);
        JsonNode hello = handshake.read(input);
        require(hello != null && hello.get("kind").textValue().equals("hello"), "INVALID_STATE");
        JsonNode offer = hello.get("data");
        fields(offer, "versions", "maxFrameBytes", "plugin", "providers");
        requireVersion(offer.get("versions"));
        int frameLimit = integer(offer.get("maxFrameBytes"), 1024, JsonFrameCodec.MAX_FRAME_BYTES);
        JsonNode providers = offer.get("providers");
        require(providers.isArray() && providers.size() == 1, "UNSUPPORTED_PROVIDER");
        JsonNode choice = providers.get(0);
        fields(choice, "kind", "name");
        require(kind.equals(choice.get("kind").textValue()) && choice.get("name").isTextual(),
                "UNSUPPORTED_PROVIDER");
        var selected = new ExtensionProcess.Provider(kind, choice.get("name").textValue());
        JsonNode plugin = offer.get("plugin");
        fields(plugin, "id", "version");
        new ExtensionProcess.Declaration(plugin.get("id").textValue(), plugin.get("version").textValue(), Set.of(selected));
        ProviderBinding provider = loader.load(selected.name());

        String session = hello.get("sessionId").textValue();
        ObjectNode accepted = JSON.createObjectNode().put("maxFrameBytes", frameLimit);
        accepted.set("plugin", plugin);
        accepted.putArray("providers").add(selector(selected));
        send(handshake, output, "ready", session, "0", accepted);

        var codec = new ExtensionCodec(frameLimit);
        for (long sequence = 1; sequence > 0; sequence++) {
            JsonNode request = codec.read(input);
            if (request == null) return;
            String id = Long.toString(sequence);
            require(session.equals(request.get("sessionId").textValue()) && id.equals(request.get("id").textValue()),
                    "ID_MISMATCH");
            JsonNode data = request.get("data");
            if (request.get("kind").textValue().equals("shutdown")) {
                fields(data, "timeoutMs");
                integer(data.get("timeoutMs"), 1, 300000);
                send(codec, output, "stopped", session, id, JSON.createObjectNode());
                return;
            }
            require(request.get("kind").textValue().equals("request"), "INVALID_STATE");
            fields(data, "provider", "timeoutMs", "input");
            require(selector(selected).equals(data.get("provider")), "ID_MISMATCH");
            integer(data.get("timeoutMs"), 1, 300000);
            ProviderRequest call;
            try {
                call = provider.decode(data.get("input"), frameLimit);
            } catch (IOException malformed) {
                sendError(codec, output, session, id, MALFORMED);
                return;
            }
            ObjectNode result;
            try {
                result = call.invoke();
            } catch (IOException | RuntimeException failure) {
                sendError(codec, output, session, id, "PROVIDER_ERROR");
                return;
            }
            ObjectNode reply = JSON.createObjectNode();
            reply.set("provider", selector(selected));
            reply.set("value", result);
            send(codec, output, "result", session, id, reply);
        }
    }

    static <T> T load(Class<T> service, ClassLoader verifiedArtifact) throws IOException {
        var candidates = ServiceLoader.load(service, verifiedArtifact).stream()
                .filter(candidate -> candidate.type().getClassLoader() == verifiedArtifact)
                .limit(2).toList();
        require(candidates.size() == 1, "UNSUPPORTED_PROVIDER");
        T provider = candidates.getFirst().get();
        require(provider.getClass().getClassLoader() == verifiedArtifact, "UNSUPPORTED_PROVIDER");
        return provider;
    }

    private static void requireVersion(JsonNode versions) throws IOException {
        require(versions.isArray() && !versions.isEmpty() && versions.size() <= 8, MALFORMED);
        Set<String> offered = new HashSet<>();
        for (JsonNode version : versions) {
            require(version.isTextual() && offered.add(version.textValue()), MALFORMED);
        }
        require(offered.contains("1.0"), "INCOMPATIBLE_VERSION");
    }

    private static int integer(JsonNode value, int minimum, int maximum) throws IOException {
        require(value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum, MALFORMED);
        return value.intValue();
    }

    private static void sendError(ExtensionCodec codec, OutputStream output, String session, String id, String code)
            throws IOException {
        send(codec, output, "error", session, id, JSON.createObjectNode().put("code", code));
    }

    private static void send(ExtensionCodec codec, OutputStream output, String kind, String session,
                             String id, ObjectNode data) throws IOException {
        ObjectNode reply = JSON.createObjectNode().put("protocol", "faultora.extension").put("kind", kind)
                .put("version", "1.0").put("sessionId", session).put("id", id);
        reply.set("data", data);
        codec.write(output, reply);
        output.flush();
    }
}
