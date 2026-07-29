package dev.faultora.engine.fault;

import dev.faultora.model.identifier.NodeId;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultSessionTest {

    static final class CountingProvider implements FaultProvider {
        final AtomicInteger injections = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        public Set<String> capabilities() {
            return Set.of("stub-fault");
        }

        @Override
        public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
            int id = injections.incrementAndGet();
            // Clamp activation below the expiry: with a 1ms fault duration the
            // clock may already have reached hardExpiryMs.
            long activatedAtMs = Math.min(System.currentTimeMillis(), context.hardExpiryMs() - 1);
            return new ActiveFault("stub-" + id, faultType, context.targetScope(),
                    activatedAtMs, context.hardExpiryMs(), "forget");
        }

        @Override
        public void rollback(ActiveFault fault, FaultContext context) {
            rollbacks.incrementAndGet();
        }
    }

    @Test
    void rollbackIsExactlyOnceUnderStopVersusExpiryRace() {
        // Race an explicit stop against the hard-expiry watchdog many times:
        // the provider must see exactly one rollback per fault, no matter who wins.
        int iterations = 200;
        for (int i = 0; i < iterations; i++) {
            CountingProvider provider = new CountingProvider();
            List<String> statuses = new CopyOnWriteArrayList<>();
            try (FaultSession session = new FaultSession(
                    (fault, status) -> statuses.add(status))) {
                ActiveFault fault = session.start(
                        provider, new NodeId("inject"), "stub-fault", "*", Map.of(), 1);
                session.rollback(fault.handle(), FaultSession.REASON_FAULT_STOP);
            }
            assertThat(provider.rollbacks.get())
                    .as("iteration %d", i)
                    .isEqualTo(1);
            assertThat(statuses).as("iteration %d", i).hasSize(1);
        }
    }

    @Test
    void closeRollsBackEverythingStillActive() {
        CountingProvider provider = new CountingProvider();
        List<String> statuses = new CopyOnWriteArrayList<>();
        FaultSession session = new FaultSession((fault, status) -> statuses.add(status));

        session.start(provider, new NodeId("a"), "stub-fault", "*", Map.of(), 60_000);
        session.start(provider, new NodeId("b"), "stub-fault", "*", Map.of(), 60_000);
        assertThat(session.pendingCount()).isEqualTo(2);

        session.close();

        assertThat(provider.rollbacks.get()).isEqualTo(2);
        assertThat(session.pendingCount()).isZero();
        assertThat(statuses).containsOnly(FaultSession.REASON_RUN_END);
    }

    @Test
    void rollbackFailureIsReportedButStillCountsAsRolledBack() {
        List<String> statuses = new CopyOnWriteArrayList<>();
        FaultProvider throwingProvider = new FaultProvider() {
            @Override
            public Set<String> capabilities() {
                return Set.of("stub-fault");
            }

            @Override
            public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
                return new ActiveFault("boom-1", faultType, context.targetScope(),
                        System.currentTimeMillis(), context.hardExpiryMs(), "forget");
            }

            @Override
            public void rollback(ActiveFault fault, FaultContext context) {
                throw new IllegalStateException("rollback exploded");
            }
        };

        try (FaultSession session = new FaultSession((fault, status) -> statuses.add(status))) {
            ActiveFault fault = session.start(
                    throwingProvider, new NodeId("inject"), "stub-fault", "*", Map.of(), 60_000);
            session.rollback(fault.handle(), FaultSession.REASON_FAULT_STOP);
            assertThat(session.pendingCount()).isZero();
        }

        assertThat(statuses).containsExactly("fault-stop-rollback-failed");
    }

    @Test
    void aFaultInjectedAsTheRunEndsIsRolledBackRatherThanLeftBehind() {
        // The exact interleaving: the run ends — cancelled, or past its
        // deadline — after the provider has injected but before the session
        // knows about it. Driving close() from inside inject() reproduces it
        // deterministically instead of hoping a thread lands in the window.
        CountingProvider provider = new CountingProvider();
        List<String> statuses = new CopyOnWriteArrayList<>();
        FaultSession session = new FaultSession((fault, status) -> statuses.add(status));

        FaultProvider closesTheSessionMidInjection = new FaultProvider() {
            @Override
            public Set<String> capabilities() {
                return provider.capabilities();
            }

            @Override
            public ActiveFault inject(
                    String faultType, Map<String, Object> params, FaultContext context) {
                ActiveFault fault = provider.inject(faultType, params, context);
                session.close();
                return fault;
            }

            @Override
            public void rollback(ActiveFault fault, FaultContext context) {
                provider.rollback(fault, context);
            }
        };

        assertThatThrownBy(() -> session.start(closesTheSessionMidInjection,
                new NodeId("inject"), "stub-fault", "*", Map.of(), 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back");

        // What the class exists to guarantee: nothing injected stays injected.
        assertThat(provider.rollbacks.get()).isEqualTo(1);
        assertThat(session.pendingCount()).isZero();
        assertThat(statuses).containsExactly(FaultSession.REASON_RUN_END);
    }

    @Test
    void aClosedSessionRefusesToInjectAtAll() {
        CountingProvider provider = new CountingProvider();
        FaultSession session = new FaultSession((fault, status) -> { });
        session.close();

        assertThatThrownBy(() -> session.start(
                provider, new NodeId("inject"), "stub-fault", "*", Map.of(), 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no further fault");
        assertThat(provider.injections.get()).isZero();
    }

    @Test
    void handleForNodeResolvesTheStartedFault() {
        CountingProvider provider = new CountingProvider();
        try (FaultSession session = new FaultSession((fault, status) -> { })) {
            ActiveFault fault = session.start(
                    provider, new NodeId("inject"), "stub-fault", "*", Map.of(), 60_000);
            assertThat(session.handleForNode(new NodeId("inject"))).isEqualTo(fault.handle());
            assertThat(session.handleForNode(new NodeId("other"))).isNull();
        }
    }
}
