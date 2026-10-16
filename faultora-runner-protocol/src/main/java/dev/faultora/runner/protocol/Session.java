package dev.faultora.runner.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The answer to a registration: a session, or a reason there is none.
 * <p>
 * One type with two shapes rather than an exception, because a refusal is an
 * ordinary outcome here — a runner that speaks an older protocol than the
 * dispatcher is a deployment mid-upgrade, not a fault.
 *
 * @param protocolVersion the version both sides settled on, null when refused
 * @param sessionId       identifies this registration in later messages, null
 *                        when refused
 * @param refusal         why there is no session, null when there is one
 */
public record Session(String protocolVersion, String sessionId, Refusal refusal) {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Decode before negotiation so malformed JSON receives a named, data-free refusal. */
    public static Session answerJson(String registrationJson, String sessionId) {
        if (registrationJson == null || registrationJson.length() > Registration.MAX_JSON_BYTES
                || registrationJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > Registration.MAX_JSON_BYTES) {
            return malformed();
        }
        try {
            var tree = MAPPER.readTree(registrationJson);
            if (tree == null || !tree.isObject() || !tree.path("runnerId").isTextual()
                    || !tree.path("agentVersion").isTextual() || !tree.path("protocols").isArray()) {
                return malformed();
            }
            for (var version : tree.path("protocols")) {
                if (!version.isTextual()) {
                    return malformed();
                }
            }
            var capabilities = tree.path("capabilities");
            if (!capabilities.isMissingNode() && !capabilities.isNull()) {
                if (!capabilities.isArray()) {
                    return malformed();
                }
                for (var capability : capabilities) {
                    if (!capability.isTextual()) {
                        return malformed();
                    }
                }
            }
            return answer(MAPPER.treeToValue(tree, Registration.class), sessionId);
        } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException invalid) {
            return malformed();
        }
    }

    private static Session malformed() {
        return refused(Refusal.of(Refusal.Reason.MALFORMED_REGISTRATION,
                "registration must provide a runner identity and valid protocol preferences"));
    }

    /** A registration that was accepted. */
    public static Session accepted(String protocolVersion, String sessionId) {
        return new Session(protocolVersion, sessionId, null);
    }

    /** A registration that was not. */
    public static Session refused(Refusal refusal) {
        return new Session(null, null, refusal);
    }

    /**
     * Whether there is a session.
     * <p>
     * Not part of the wire. Jackson reads a boolean {@code isX()} as a
     * property, so without this a session serialized an {@code accepted} field
     * that it then refused to read back — a message that could be sent and not
     * received, which is the one thing a protocol may not do.
     */
    @JsonIgnore
    public boolean isAccepted() {
        return refusal == null;
    }

    /**
     * Answer a registration by negotiating a version.
     * <p>
     * The whole of protocol negotiation, in one place so that both sides of a
     * test are looking at the same rule.
     */
    public static Session answer(Registration registration, String sessionId) {
        if (registration == null || !registration.wellFormed()
                || sessionId == null || sessionId.isBlank()) {
            return malformed();
        }
        String agreed = ProtocolVersion.negotiate(registration.protocols());
        if (agreed == null) {
            return refused(Refusal.of(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION,
                    "runner '" + registration.runnerId() + "' speaks "
                            + registration.protocols() + " and this side speaks "
                            + ProtocolVersion.SUPPORTED));
        }
        return accepted(agreed, sessionId);
    }
}
