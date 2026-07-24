package dev.faultora.faults.local;

import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFaultProviderTest {

    private final LocalFaultProvider provider = new LocalFaultProvider();

    private FaultContext context(String scope, long expiresInMs) {
        return new FaultContext(scope, System.currentTimeMillis() + expiresInMs, Map.of());
    }

    @Test
    void advertisesLocalFaultCapabilities() {
        assertThat(provider.capabilities()).containsExactlyInAnyOrder(
                "http-latency", "http-error", "http-response-loss");
    }

    @Test
    void injectRegistersFaultBeforeReturning() {
        ActiveFault fault = provider.inject(
                "http-latency", Map.of("delayMs", 50), context("payments", 60_000));

        assertThat(provider.activeCount()).isEqualTo(1);
        assertThat(fault.faultType()).isEqualTo("http-latency");
        assertThat(fault.targetScope()).isEqualTo("payments");
        assertThat(provider.activeFaultsFor("payments", System.currentTimeMillis()))
                .extracting(f -> f.fault().handle())
                .containsExactly(fault.handle());
    }

    @Test
    void rollbackIsIdempotent() {
        ActiveFault fault = provider.inject(
                "http-error", Map.of(), context("*", 60_000));

        provider.rollback(fault, context("*", 60_000));
        provider.rollback(fault, context("*", 60_000));

        assertThat(provider.activeCount()).isZero();
        assertThat(provider.activeFaultsFor("anything", System.currentTimeMillis())).isEmpty();
    }

    @Test
    void rejectsUnknownFaultType() {
        assertThatThrownBy(() -> provider.inject("kafka-lag", Map.of(), context("*", 60_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kafka-lag");
        assertThat(provider.activeCount()).isZero();
    }

    @Test
    void rejectsLatencyWithoutPositiveBoundedDelay() {
        assertThatThrownBy(() -> provider.inject(
                "http-latency", Map.of(), context("*", 60_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.inject(
                "http-latency", Map.of("delayMs", 0), context("*", 60_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.inject(
                "http-latency", Map.of("delayMs", LocalFaultProvider.MAX_LATENCY_MS + 1),
                context("*", 60_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredFaultStopsMatchingEvenBeforeRollback() {
        ActiveFault fault = provider.inject(
                "http-error", Map.of(), context("payments", 10));

        assertThat(provider.activeFaultsFor("payments", fault.hardExpiryMs())).isEmpty();
        assertThat(provider.activeFaultsFor("payments", fault.hardExpiryMs() - 1)).hasSize(1);
    }

    @Test
    void scopeMatchingHonorsWildcardAndTargetId() {
        provider.inject("http-error", Map.of(), context("payments", 60_000));
        provider.inject("http-error", Map.of(), context("*", 60_000));

        long now = System.currentTimeMillis();
        assertThat(provider.activeFaultsFor("payments", now)).hasSize(2);
        assertThat(provider.activeFaultsFor("orders", now)).hasSize(1);
    }

    @Test
    void blankScopeDefaultsToAllTargets() {
        ActiveFault fault = provider.inject("http-error", Map.of(), context("", 60_000));
        assertThat(fault.targetScope()).isEqualTo("*");
        assertThat(provider.activeFaultsFor("anything", System.currentTimeMillis())).hasSize(1);
    }

    @Test
    void activeFaultsAreReturnedInActivationOrder() {
        ActiveFault first = provider.inject("http-latency", Map.of("delayMs", 5), context("*", 60_000));
        ActiveFault second = provider.inject("http-response-loss", Map.of(), context("*", 60_000));

        assertThat(provider.activeFaultsFor("any", System.currentTimeMillis()))
                .extracting(f -> f.fault().handle())
                .containsExactly(first.handle(), second.handle());
    }
}
