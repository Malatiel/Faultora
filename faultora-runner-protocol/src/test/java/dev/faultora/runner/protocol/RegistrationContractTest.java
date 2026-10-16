package dev.faultora.runner.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationContractTest {
    private static final String VALID = """
            {"runnerId":"runner-1","agentVersion":"0.10.1","protocols":["1"],"capabilities":["http"]}
            """;

    @Test
    void versionOneRegistrationHasAnIndependentFrozenShape() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(mapper.writeValueAsString(new Registration(
                "runner-1", "0.10.1", List.of("1"), Set.of("http")))))
                .isEqualTo(mapper.readTree(VALID));
        assertThat(Session.answerJson(VALID, "session").protocolVersion()).isEqualTo("1");
        assertThat(Session.answerJson(VALID.replace("\"1\"", "\"faultora.dev/v1\""), "session")
                .refusal().reason()).isEqualTo(Refusal.Reason.MALFORMED_REGISTRATION);
    }

    @Test
    void futureAndCurrentMajorCanBeAdvertisedTogether() {
        assertThat(Session.answerJson(VALID.replace("[\"1\"]", "[\"99\",\"1\"]"), "session")
                .protocolVersion()).isEqualTo("1");
        assertThat(Session.answerJson(VALID.replace("[\"1\"]", "[\"99\"]"), "session")
                .refusal().reason()).isEqualTo(Refusal.Reason.UNSUPPORTED_PROTOCOL_VERSION);
    }

    @Test
    void additiveRegistrationFieldsAreCompatible() {
        assertThat(Session.answerJson(VALID.replace("\"capabilities\"", "\"future\":true,\"capabilities\""),
                "session").isAccepted()).isTrue();
    }

    @Test
    void invalidRegistrationShapesHaveOneNamedSanitizedRefusal() {
        for (String json : List.of("null", "[]", "{", "{}",
                VALID + "{}",
                VALID.replace("\"runner-1\"", "\"\""),
                VALID.replace("\"0.10.1\"", "null"),
                VALID.replace("[\"1\"]", "[]"),
                VALID.replace("[\"1\"]", "[null]"),
                VALID.replace("[\"1\"]", "[1]"),
                VALID.replace("[\"1\"]", "[\"1\",\"1\"]"),
                VALID.replace("[\"1\"]", "[\"01\"]"),
                VALID.replace("\"http\"", "null"),
                VALID.replace("\"runner-1\"", "\"SYNTHETIC_PRIVATE_VALUE/invalid\""),
                VALID.replace("\"runnerId\"", "\"runnerId\":\"duplicate\",\"runnerId\""),
                " ".repeat(65_537))) {
            Session refused = Session.answerJson(json, "session");
            assertThat(refused.isAccepted()).as(json).isFalse();
            assertThat(refused.refusal().reason()).isEqualTo(Refusal.Reason.MALFORMED_REGISTRATION);
            assertThat(refused.refusal().describe()).doesNotContain("SYNTHETIC_PRIVATE_VALUE");
        }
    }

    @Test
    void registrationLimitCountsUtf8BytesRatherThanCharacters() {
        String json = VALID.replace("\"capabilities\"", "\"future\":\"" + "я".repeat(33_000)
                + "\",\"capabilities\"");
        assertThat(json.length()).isLessThan(Registration.MAX_JSON_BYTES);
        assertThat(Session.answerJson(json, "session").refusal().reason())
                .isEqualTo(Refusal.Reason.MALFORMED_REGISTRATION);
    }

    @Test
    void directRegistrationsWithNullEntriesAlsoReceiveARefusal() {
        Registration invalid = new Registration("runner", "0.10.1",
                Arrays.asList("1", null), Set.of());
        assertThat(Session.answer(invalid, "session").refusal().reason())
                .isEqualTo(Refusal.Reason.MALFORMED_REGISTRATION);
        assertThat(Session.answerJson(VALID, null).isAccepted()).isFalse();
    }

    @Test
    void theAdvertisedPreferenceIsDefensivelyCopied() {
        var offered = new java.util.ArrayList<>(List.of("1"));
        Registration registration = new Registration("runner", "0.10.1", offered, Set.of());
        offered.clear();
        assertThat(Session.answer(registration, "session").isAccepted()).isTrue();
    }
}
