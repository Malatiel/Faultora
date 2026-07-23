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

    // ---- contentTypeAllowlist tests ----

    @Test
    void bodyCapturedWhenContentTypeInAllowlist() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"ok\":true}".getBytes(), "application/json");

        assertThat(evidence.responseBody()).isPresent();
        assertThat(evidence.responseJson()).isPresent();
    }

    @Test
    void bodySkippedWhenContentTypeNotInAllowlist() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("<html>secret</html>".getBytes(), "text/html");

        assertThat(evidence.responseBody()).isEmpty();
        assertThat(evidence.responseJson()).isEmpty();
    }

    @Test
    void bodyCapturedWhenAllowlistEmpty() {
        // Empty allowlist = all content types allowed
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"ok\":true}".getBytes(), "application/xml");

        assertThat(evidence.responseBody()).isPresent();
    }

    @Test
    void bodySkippedWhenContentTypeNullWithAllowlist() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"ok\":true}".getBytes(), null);

        assertThat(evidence.responseBody()).isEmpty();
        assertThat(evidence.responseJson()).isEmpty();
    }

    @Test
    void bodyCapturedWithContentTypeParameters() {
        // "application/json; charset=utf-8" should match "application/json"
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0, List.of(),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"ok\":true}".getBytes(), "application/json; charset=utf-8");

        assertThat(evidence.responseBody()).isPresent();
    }

    // ---- redactPaths tests ----

    @Test
    void redactPathsRedactsMatchingFields() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0,
                List.of("creditCard.cvv", "ssn"),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"creditCard\":{\"number\":\"4111\",\"cvv\":\"123\"},\"ssn\":\"123-45-6789\",\"name\":\"Alice\"}".getBytes());

        assertThat(evidence.responseJson()).isPresent();
        var json = evidence.responseJson().get();
        assertThat(json.path("creditCard").path("cvv").asText()).isEqualTo("***");
        assertThat(json.path("ssn").asText()).isEqualTo("***");
        // Non-redacted fields preserved
        assertThat(json.path("creditCard").path("number").asText()).isEqualTo("4111");
        assertThat(json.path("name").asText()).isEqualTo("Alice");
    }

    @Test
    void redactPathsHandlesDollarPrefix() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0,
                List.of("$.secret"),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"secret\":\"top\",\"public\":\"info\"}".getBytes());

        var json = evidence.responseJson().get();
        assertThat(json.path("secret").asText()).isEqualTo("***");
        assertThat(json.path("public").asText()).isEqualTo("info");
    }

    @Test
    void redactPathsHandlesArrayElements() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0,
                List.of("items.cardNumber"),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"items\":[{\"cardNumber\":\"4111\",\"qty\":1},{\"cardNumber\":\"5555\",\"qty\":2}]}".getBytes());

        var json = evidence.responseJson().get();
        assertThat(json.path("items").get(0).path("cardNumber").asText()).isEqualTo("***");
        assertThat(json.path("items").get(1).path("cardNumber").asText()).isEqualTo("***");
        assertThat(json.path("items").get(0).path("qty").asInt()).isEqualTo(1);
    }

    @Test
    void redactPathsNoopWhenEmpty() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0,
                List.of(),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"a\":1}".getBytes());

        var json = evidence.responseJson().get();
        assertThat(json.path("a").asInt()).isEqualTo(1);
    }

    @Test
    void redactPathsAppliedToSerializedBody() {
        // After redaction, responseBody() should contain the redacted JSON
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                0, 0,
                List.of("token"),
                Set.of(), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"token\":\"secret123\",\"id\":42}".getBytes());

        assertThat(evidence.responseBody()).isPresent();
        String redacted = new String(evidence.responseBody().get());
        assertThat(redacted).contains("***");
        assertThat(redacted).doesNotContain("secret123");
        assertThat(redacted).contains("\"id\":42");
    }

    @Test
    void oversizedJsonWithRedactionIsOmittedInsteadOfStoredRaw() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                20, 0,
                List.of("token"),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body(
                "{\"token\":\"must-never-leak\",\"padding\":\"0123456789\"}".getBytes(),
                "application/json");

        assertThat(evidence.responseBody()).isEmpty();
        assertThat(evidence.responseJson()).isEmpty();
    }

    @Test
    void invalidJsonWithRedactionIsOmitted() {
        EvidencePolicy policy = new EvidencePolicy(
                true, false,
                Set.of(),
                1024, 0,
                List.of("token"),
                Set.of("application/json"), "session");

        NodeEvidence evidence = new NodeEvidence(policy);
        evidence.body("{\"token\":\"must-never-leak\"".getBytes(), "application/json");

        assertThat(evidence.responseBody()).isEmpty();
        assertThat(evidence.responseJson()).isEmpty();
    }
}
