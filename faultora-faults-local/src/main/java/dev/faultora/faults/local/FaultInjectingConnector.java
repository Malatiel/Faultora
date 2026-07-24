package dev.faultora.faults.local;

import dev.faultora.faults.local.LocalFaultProvider.RegisteredFault;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connector decorator that applies active in-process faults to outbound requests.
 * <p>
 * Fault application order for a single request:
 * <ol>
 *   <li>{@code http-error} — the request is rejected before it is sent;</li>
 *   <li>{@code http-latency} — the request is delayed (delays stack);</li>
 *   <li>{@code http-response-loss} — the request is delivered, the response is
 *       discarded and replaced with a timeout-category error.</li>
 * </ol>
 * The connector never fabricates target responses: injected failures surface as
 * normalized errors whose code starts with {@code FAULT_}, so evidence cannot be
 * mistaken for target behavior.
 */
public final class FaultInjectingConnector implements Connector {

    private final Connector delegate;
    private final LocalFaultProvider faults;

    public FaultInjectingConnector(Connector delegate, LocalFaultProvider faults) {
        this.delegate = delegate;
        this.faults = faults;
    }

    @Override
    public ProtocolId protocol() {
        return delegate.protocol();
    }

    @Override
    public Set<String> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
        return delegate.prepare(target, context);
    }

    @Override
    public OperationResult execute(
            PreparedTarget preparedTarget,
            OperationDefinition operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        String targetId = preparedTarget.targetDefinition().id().value();
        List<RegisteredFault> activeFaults =
                faults.activeFaultsFor(targetId, System.currentTimeMillis());
        if (activeFaults.isEmpty()) {
            return delegate.execute(preparedTarget, operation, inputs, context);
        }

        for (RegisteredFault registered : activeFaults) {
            if (LocalFaultProvider.HTTP_ERROR.equals(registered.fault().faultType())) {
                return OperationResult.failure(new NormalizedError(
                        NormalizedError.ErrorCategory.NETWORK,
                        "FAULT_INJECTED_ERROR",
                        "Injected fault '" + registered.fault().handle()
                                + "' rejected the request before it reached the target",
                        true,
                        Map.of("faultHandle", registered.fault().handle())), 0);
            }
        }

        long delayMs = 0;
        for (RegisteredFault registered : activeFaults) {
            if (LocalFaultProvider.HTTP_LATENCY.equals(registered.fault().faultType())) {
                delayMs += toLong(registered.params().get("delayMs"));
            }
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return OperationResult.failure(new NormalizedError(
                        NormalizedError.ErrorCategory.CANCELLED,
                        "FAULT_DELAY_INTERRUPTED",
                        "Injected latency was interrupted before the request was sent",
                        false, Map.of()), delayMs);
            }
        }

        OperationResult result = delegate.execute(preparedTarget, operation, inputs, context);
        long totalDurationMs = result.durationMs() + delayMs;

        RegisteredFault responseLoss = activeFaults.stream()
                .filter(f -> LocalFaultProvider.HTTP_RESPONSE_LOSS.equals(f.fault().faultType()))
                .findFirst().orElse(null);
        if (responseLoss != null && result.error() == null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("faultHandle", responseLoss.fault().handle());
            metadata.put("discardedStatus", result.statusCode());
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.TIMEOUT,
                    "FAULT_RESPONSE_LOSS",
                    "Injected fault '" + responseLoss.fault().handle()
                            + "' discarded the response after the request was delivered",
                    true, metadata), totalDurationMs);
        }

        if (delayMs == 0) {
            return result;
        }
        return new OperationResult(
                result.statusCode(), result.headers(), result.body(),
                totalDurationMs, result.error(),
                result.protocolEvidence(), result.evidenceDigests());
    }

    @Override
    public void release(PreparedTarget preparedTarget) {
        delegate.release(preparedTarget);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
