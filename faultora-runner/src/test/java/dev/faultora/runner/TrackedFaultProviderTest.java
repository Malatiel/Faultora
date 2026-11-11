package dev.faultora.runner;

import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedFaultProviderTest {
    private static final String ATTEMPT = "123e4567-e89b-12d3-a456-426614174010";
    private static final String ACTIVATION = "123e4567-e89b-12d3-a456-426614174011";
    private static final String PROVIDER = ContentDigest.sha256Uri("fixture-provider");
    private static final FaultContext CONTEXT = new FaultContext("fixture-target", 1000L,
            Map.of(FaultContext.ACTIVATION_ID, ACTIVATION));

    @TempDir Path run;

    @Test
    void delegateSeesDurableIntentAndOnlyConfirmedRollbackCompletesIt() throws Exception {
        var obligations = new FaultObligations(run, ATTEMPT);
        var pending = new FaultObligations.Entry(ACTIVATION, PROVIDER, CONTEXT.hardExpiryMs());
        var rollbacks = new AtomicInteger();
        FaultProvider delegate = new FaultProvider() {
            @Override public Set<String> capabilities() { return Set.of("fixture"); }

            @Override
            public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
                try {
                    assertThat(new FaultObligations(run, ATTEMPT).pending()).containsExactly(pending);
                } catch (IOException failed) {
                    throw new AssertionError(failed);
                }
                return new ActiveFault("fixture-handle", type, context.targetScope(),
                        1L, context.hardExpiryMs(), "fixture");
            }

            @Override
            public void rollback(ActiveFault fault, FaultContext context) {
                if (rollbacks.getAndIncrement() == 0) throw new IllegalStateException("fixture rollback failed");
            }
        };
        var provider = new TrackedFaultProvider(delegate, obligations, PROVIDER);
        var active = provider.inject("fixture", Map.of(), CONTEXT);

        assertThatThrownBy(() -> provider.rollback(active, CONTEXT)).isInstanceOf(IllegalStateException.class);
        assertThat(obligations.pending()).containsExactly(pending);
        provider.rollback(active, CONTEXT);
        assertThat(new FaultObligations(run, ATTEMPT).pending()).isEmpty();
    }

    @Test
    void unavailableLedgerPreventsProviderInvocation() throws Exception {
        Files.createFile(run.resolve("fault-obligations"));
        FaultProvider delegate = new FaultProvider() {
            @Override public Set<String> capabilities() { return Set.of("fixture"); }

            @Override
            public ActiveFault inject(String type, Map<String, Object> params, FaultContext context) {
                throw new AssertionError("Provider must not be invoked");
            }

            @Override
            public void rollback(ActiveFault fault, FaultContext context) {
                throw new AssertionError("Provider must not be invoked");
            }
        };
        var provider = new TrackedFaultProvider(delegate, new FaultObligations(run, ATTEMPT), PROVIDER);
        assertThatThrownBy(() -> provider.inject("fixture", Map.of(), CONTEXT))
                .isInstanceOf(UncheckedIOException.class).hasMessage("FAULT_OBLIGATION_UNAVAILABLE");
    }
}
