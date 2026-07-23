package dev.faultora.engine.evidence;

import dev.faultora.model.security.EvidencePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeEvidenceTest {

    @Test
    void headersFilteredByDenylist() {
        EvidencePolicy policy = new EvidencePolicy(
                false, true, // captureHeaders=true
                Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                0, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.headers(Map.of(
                "content-type", List.of("application/json"),
                "authorization", List.of("Bearer secret-token"),
                "cookie", List.of("session=abc123"),
                "x-request-id", List.of("req-1")));

        assertThat(evidence.responseHeaders())
                .containsKey("content-type")
                .containsKey("x-request-id")
                .doesNotContainKey("authorization")
                .doesNotContainKey("cookie");
    }

    @Test
    void headersEmptyWhenCaptureDisabled() {
        EvidencePolicy policy = EvidencePolicy.MINIMAL; // captureHeaders=false
        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.headers(Map.of("content-type", List.of("application/json")));

        assertThat(evidence.responseHeaders()).isEmpty();
    }

    @Test
    void headersPreservedWhenNotInDenylist() {
        EvidencePolicy policy = new EvidencePolicy(
                false, true,
                Set.of("authorization"),
                0, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.headers(Map.of(
                "content-type", List.of("application/json"),
                "x-custom-header", List.of("value1", "value2")));

        assertThat(evidence.responseHeaders())
                .hasSize(2)
                .containsEntry("content-type", List.of("application/json"))
                .containsEntry("x-custom-header", List.of("value1", "value2"));
    }

    @Test
    void emptyHeadersHandled() {
        EvidencePolicy policy = new EvidencePolicy(
                false, true, Set.of(), 0, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.headers(Map.of());

        assertThat(evidence.responseHeaders()).isEmpty();
    }

    @Test
    void nullHeadersHandled() {
        EvidencePolicy policy = new EvidencePolicy(
                false, true, Set.of(), 0, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.headers(null);

        assertThat(evidence.responseHeaders()).isEmpty();
    }

    @Test
    void bodyTruncatedByMaxBodyBytes() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                10, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        byte[] largeBody = "{\"a\":\"0123456789abcdef\"}".getBytes();
        evidence.body(largeBody);

        assertThat(evidence.responseBody()).isPresent();
        assertThat(evidence.responseBody().get().length).isEqualTo(10);
    }

    @Test
    void bodyNotTruncatedWhenWithinMaxBodyBytes() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                1000, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        byte[] smallBody = "{\"a\":1}".getBytes();
        evidence.body(smallBody);

        assertThat(evidence.responseBody()).isPresent();
        assertThat(evidence.responseBody().get().length).isEqualTo(smallBody.length);
    }

    @Test
    void bodyNotTruncatedWhenMaxBodyBytesZero() {
        // maxBodyBytes=0 means no limit
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(), Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        byte[] body = "{\"a\":\"0123456789\"}".getBytes();
        evidence.body(body);

        assertThat(evidence.responseBody()).isPresent();
        assertThat(evidence.responseBody().get().length).isEqualTo(body.length);
    }
}
