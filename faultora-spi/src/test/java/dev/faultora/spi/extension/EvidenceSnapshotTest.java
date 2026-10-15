package dev.faultora.spi.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.EvidenceView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an assertion's inputs can leave this process at all.
 * <p>
 * ADR-023 says three SPI contracts are pure functions over what they are handed
 * and can therefore run where their heap and sockets are not the run's. For
 * assertions that is a claim about {@link EvidenceView} — an interface whose
 * every method happens to return data, which is exactly the sort of thing that
 * quietly stops being true.
 * <p>
 * So it is measured: a view goes to a snapshot, over a wire as text, back to a
 * view, and every accessor is compared. The last test is the one that will
 * matter later — it fails when the interface grows a method the snapshot does
 * not carry, which is the day the claim would otherwise become false without
 * anybody noticing.
 */
class EvidenceSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] BODY =
            "{\"id\":\"pay-1\",\"amount\":2500}".getBytes(StandardCharsets.UTF_8);

    /** A view with something in every accessor, including the awkward ones. */
    private static EvidenceView everything() {
        return new EvidenceView() {
            @Override
            public Optional<Integer> statusCode() {
                return Optional.of(201);
            }

            @Override
            public Map<String, List<String>> responseHeaders() {
                return Map.of("content-type", List.of("application/json"));
            }

            @Override
            public Optional<byte[]> responseBody() {
                return Optional.of(BODY);
            }

            @Override
            public Optional<JsonNode> responseJson() {
                try {
                    return Optional.of(JSON.readTree(BODY));
                } catch (Exception impossible) {
                    throw new AssertionError(impossible);
                }
            }

            @Override
            public long durationMs() {
                return 412;
            }

            @Override
            public Optional<NormalizedError> error() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> protocolEvidence() {
                return Map.of("rowCount", 2);
            }
        };
    }

    @Test
    void everythingAnAssertionCanSeeSurvivesTheTrip() throws Exception {
        EvidenceView before = everything();

        String wire = JSON.writeValueAsString(EvidenceSnapshot.of(before));
        EvidenceView after = JSON.readValue(wire, EvidenceSnapshot.class).asView();

        assertThat(after.statusCode()).isEqualTo(before.statusCode());
        assertThat(after.responseHeaders()).isEqualTo(before.responseHeaders());
        assertThat(after.responseBody().orElseThrow())
                .as("bytes, base64 across a text transport and back")
                .isEqualTo(before.responseBody().orElseThrow());
        assertThat(after.responseJson().orElseThrow())
                .as("parsed from the bytes rather than carried beside them")
                .isEqualTo(before.responseJson().orElseThrow());
        assertThat(after.durationMs()).isEqualTo(before.durationMs());
        assertThat(after.protocolEvidence()).isEqualTo(before.protocolEvidence());
    }

    @Test
    void aRunThatSawNothingTravelsAsNothing() {
        // Evidence a policy did not permit keeping is absent, not empty, and
        // an assertion tells those apart — an absent body is indeterminate,
        // and a zero-length one is a body.
        EvidenceView nothing = EvidenceSnapshot
                .of(new EvidenceView() {
                    @Override
                    public Optional<Integer> statusCode() {
                        return Optional.empty();
                    }

                    @Override
                    public Map<String, List<String>> responseHeaders() {
                        return Map.of();
                    }

                    @Override
                    public Optional<byte[]> responseBody() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<JsonNode> responseJson() {
                        return Optional.empty();
                    }

                    @Override
                    public long durationMs() {
                        return 3;
                    }

                    @Override
                    public Optional<NormalizedError> error() {
                        return Optional.empty();
                    }

                    @Override
                    public Map<String, Object> protocolEvidence() {
                        return Map.of();
                    }
                })
                .asView();

        assertThat(nothing.statusCode()).isEmpty();
        assertThat(nothing.responseBody()).isEmpty();
        assertThat(nothing.responseJson()).isEmpty();
        assertThat(nothing.error()).isEmpty();
    }

    @Test
    void whatWentWrongCrossesAsWellAsWhatDidNot() throws Exception {
        NormalizedError failed = new NormalizedError(
                NormalizedError.ErrorCategory.TIMEOUT, "READ_TIMEOUT",
                "The target did not answer", true, Map.of());

        EvidenceSnapshot snapshot = new EvidenceSnapshot(
                null, Map.of(), null, 5000, failed, Map.of());
        EvidenceView after = JSON.readValue(
                JSON.writeValueAsString(snapshot), EvidenceSnapshot.class).asView();

        assertThat(after.error()).contains(failed);
    }

    @Test
    void theSnapshotCarriesEverythingTheInterfaceOffers() {
        // The test that matters on the day somebody adds a method. An accessor
        // the snapshot does not carry means an assertion running out of process
        // would silently see less than one running here — which is the drift
        // ADR-020 spent a release closing for evidence policy.
        List<String> accessors = java.util.Arrays.stream(EvidenceView.class.getMethods())
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(accessors)
                .as("""
                        EvidenceView has an accessor EvidenceSnapshot does not carry.

                        An assertion running out of process would see less than the \
                        same assertion running here. Add the field to the snapshot and \
                        to this list, or ADR-023's claim that assertions are pure \
                        functions over data stops being true.""")
                .containsExactly("durationMs", "error", "protocolEvidence",
                        "responseBody", "responseHeaders", "responseJson", "statusCode");
    }
}
