package dev.faultora.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.faultora.model.catalog.*;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRoundTripTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Test
    void catalogRoundTrip() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/model/catalog.json")) {
            assertThat(is).isNotNull();

            ApiCatalog original = mapper.readValue(is, ApiCatalog.class);

            // Verify structure
            assertThat(original.version().value()).isEqualTo("v1alpha1-abc123");
            assertThat(original.targets()).hasSize(1);
            assertThat(original.operations()).hasSize(1);
            assertThat(original.schemas()).hasSize(3);
            assertThat(original.authentication()).hasSize(1);
            assertThat(original.workflows()).hasSize(1);

            // Serialize and deserialize
            String json = mapper.writeValueAsString(original);
            ApiCatalog roundTripped = mapper.readValue(json, ApiCatalog.class);

            // Must be equal
            assertThat(roundTripped).isEqualTo(original);

            // Deterministic: serialize again, must be identical
            String json2 = mapper.writeValueAsString(roundTripped);
            assertThat(json2).isEqualTo(json);
        }
    }

    @Test
    void normalizedErrorSerialization() throws IOException {
        NormalizedError error = new NormalizedError(
                NormalizedError.ErrorCategory.TIMEOUT,
                "REQUEST_TIMEOUT",
                "Request exceeded 30s deadline",
                true,
                Map.of("deadlineMs", 30000)
        );

        String json = mapper.writeValueAsString(error);
        NormalizedError deserialized = mapper.readValue(json, NormalizedError.class);

        assertThat(deserialized).isEqualTo(error);
        assertThat(deserialized.category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(deserialized.retryable()).isTrue();
    }

    @Test
    void secretHandleNeverExposesValue() throws IOException {
        SecretHandle handle = new SecretHandle(
                "handle-001",
                "sk-***4a2f",
                "env",
                System.currentTimeMillis() + 3600000
        );

        String json = mapper.writeValueAsString(handle);
        assertThat(json).doesNotContain("actual-secret-value");
        assertThat(json).contains("handle-001");
        assertThat(json).contains("sk-***4a2f");

        SecretHandle deserialized = mapper.readValue(json, SecretHandle.class);
        assertThat(deserialized.handleId()).isEqualTo("handle-001");
        assertThat(deserialized.redacted()).isEqualTo("sk-***4a2f");
    }

    @Test
    void targetPolicySerialization() throws IOException {
        TargetPolicy policy = new TargetPolicy(
                Set.of(new TargetId("payment-api")),
                Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                1000, 10, 300000, 1048576,
                Set.of("http-latency"),
                Set.of("staging", "production")
        );

        String json = mapper.writeValueAsString(policy);
        TargetPolicy deserialized = mapper.readValue(json, TargetPolicy.class);

        assertThat(deserialized).isEqualTo(policy);
    }

    @Test
    void evidencePolicyMinimalConstant() {
        EvidencePolicy minimal = EvidencePolicy.MINIMAL;
        assertThat(minimal.captureBodies()).isFalse();
        assertThat(minimal.captureHeaders()).isFalse();
        assertThat(minimal.headerDenylist()).contains("authorization", "cookie");
    }

    @Test
    void runEventSerialization() throws IOException {
        RunEvent started = new RunEvent.RunStarted(
                "RUN_STARTED",
                1700000000000L,
                new RunId("run-001"),
                "sha256:abc123",
                "sha256:def456",
                42L,
                Map.of("profile", "ci")
        );

        String json = mapper.writeValueAsString(started);
        assertThat(json).contains("RUN_STARTED");

        RunEvent deserialized = mapper.readValue(json, RunEvent.class);
        assertThat(deserialized).isInstanceOf(RunEvent.RunStarted.class);
        assertThat(deserialized.runId().value()).isEqualTo("run-001");
    }

    @Test
    void typedIdEquality() {
        OperationId id1 = new OperationId("create-payment");
        OperationId id2 = new OperationId("create-payment");
        OperationId id3 = new OperationId("delete-payment");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    void typedIdRejectsInvalidValues() {
        assertThatThrownBy(() -> new OperationId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OperationId("has spaces")).isInstanceOf(IllegalArgumentException.class);
    }
}
