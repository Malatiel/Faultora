package dev.faultora.spi.evidence;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExportTest {
    private static final String MARKER = "SYNTHETIC_PRIVATE_EXPORT_VALUE";

    @ParameterizedTest
    @EnumSource(AssertionResult.Outcome.class)
    void everyAssertionOutcomeDropsProviderProseAndNestedDetails(AssertionResult.Outcome outcome) {
        AssertionResult exported = EvidenceExport.assertion(new AssertionResult(outcome, MARKER,
                Map.of("nested", Map.of("actual", MARKER))));
        assertThat(exported.outcome()).isEqualTo(outcome);
        assertThat(exported.message()).doesNotContain(MARKER);
        assertThat(exported.details()).isEmpty();
    }

    @Test
    void unknownCodeAndArbitraryMetadataCannotBecomeAnExportChannel() {
        NormalizedError exported = EvidenceExport.connectorError(new NormalizedError(
                NormalizedError.ErrorCategory.NETWORK, MARKER, MARKER, true,
                Map.of("url", MARKER, "sql", MARKER, "nested", Map.of("data", MARKER),
                        "maxBytes", MARKER, "sqlState", MARKER, "connectionCloseFailed", MARKER)));
        assertThat(exported.code()).isEqualTo("CONNECTOR_ERROR");
        assertThat(exported.category()).isEqualTo(NormalizedError.ErrorCategory.NETWORK);
        assertThat(exported.retryable()).isTrue();
        assertThat(exported.message()).doesNotContain(MARKER);
        assertThat(exported.metadata()).isEmpty();
    }

    @Test
    void expressionLikeErrorCodesCannotMasqueradeAsHttpStatuses() {
        NormalizedError exported = EvidenceExport.connectorError(new NormalizedError(
                NormalizedError.ErrorCategory.NETWORK, "HTTP_500_" + MARKER, MARKER, true, Map.of()));
        assertThat(exported.code()).isEqualTo("CONNECTOR_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAULT_INJECTED_ERROR", "FAULT_DELAY_INTERRUPTED", "FAULT_RESPONSE_LOSS"})
    void builtinFaultWrapperClassificationsSurviveWithoutTheirHandleOrProviderMessage(String code) {
        NormalizedError exported = EvidenceExport.connectorError(new NormalizedError(
                NormalizedError.ErrorCategory.NETWORK, code, MARKER, true, Map.of("handle", MARKER)));
        assertThat(exported.code()).isEqualTo(code);
        assertThat(exported.retryable()).isTrue();
        assertThat(exported.message()).doesNotContain(MARKER);
        assertThat(exported.metadata()).isEmpty();
    }

    @Test
    void documentedNumericAndClassifierFactsSurviveAndProjectionIsIdempotent() {
        NormalizedError exported = EvidenceExport.connectorError(new NormalizedError(
                NormalizedError.ErrorCategory.TARGET_ERROR, "OBSERVATION_FAILED", MARKER, false,
                Map.of("vendorCode", 7, "sqlState", "22018", "connectionCloseFailed", true,
                        "maxBytes", 1024L, "statusCode", 500, "message", MARKER)));
        assertThat(exported.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "vendorCode", 7, "sqlState", "22018", "connectionCloseFailed", true,
                "maxBytes", 1024L, "statusCode", 500));
        assertThat(EvidenceExport.connectorError(exported)).isEqualTo(exported);
        assertThat(EvidenceExport.connectorError(null)).isNull();
    }
}
