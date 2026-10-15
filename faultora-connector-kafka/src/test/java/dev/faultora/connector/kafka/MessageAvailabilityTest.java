package dev.faultora.connector.kafka;

import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.evidence.MessageEvidence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageAvailabilityTest {
    @Test
    void productionCaptureCarriesHeaderAndPayloadProvenance() {
        EvidencePolicy policy = new EvidencePolicy(true, true, Set.of("x-private"), 1024, 10,
                List.of("secret"), Set.of(), "session");
        MessageEvidence message = message("{\"secret\":\"synthetic-private\"}", policy, true);
        assertThat(message.payloadComplete()).isFalse();
        assertThat(message.payload().path("secret").asText()).isEqualTo("***");
        assertThat(message.headers()).containsEntry("public", "visible").doesNotContainKey("x-private");
        assertThat(message.headerReadable("X-PRIVATE")).isFalse();
        assertThat(message.headerReadable("authorization")).isFalse();
        assertThat(message.headerReadable("missing-public")).isTrue();
    }

    @Test
    void validJsonPrefixesAreIncompleteEvenWhenTheyParse() {
        MessageEvidence message = message("12345", policy(3), true);
        assertThat(message.payload().asInt()).isEqualTo(123);
        assertThat(message.payloadComplete()).isFalse();
    }

    @Test
    void budgetAndCaptureWithholdingAreExplicit() {
        assertThat(message("{}", policy(1024), false).payloadComplete()).isFalse();
        MessageEvidence minimal = message("{}", EvidencePolicy.MINIMAL, true);
        assertThat(minimal.payload()).isNull();
        assertThat(minimal.payloadComplete()).isFalse();
        assertThat(minimal.headerReadable("public")).isFalse();
    }

    @Test
    void aLiteralMarkerIsNotClassifiedByItsText() {
        MessageEvidence complete = message("{\"value\":\"***\"}", policy(1024), true);
        assertThat(complete.payloadComplete()).isTrue();
        assertThat(complete.payload().path("value").asText()).isEqualTo("***");
    }

    private static MessageEvidence message(String text, EvidencePolicy policy, boolean budget) {
        return Messages.evidence("events", 0, 1, 1, "key",
                Map.of("x-private", "synthetic-private", "public", "visible"),
                text.getBytes(StandardCharsets.UTF_8), policy, budget);
    }

    private static EvidencePolicy policy(int bytes) {
        return new EvidencePolicy(true, true, Set.of(), bytes, 10, List.of(), Set.of(), "session");
    }
}
