package dev.faultora.faults.local;

import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process fault provider.
 * <p>
 * Faults registered here act only on Faultora's own outbound requests (through
 * {@link FaultInjectingConnector}); they never touch the target system or any
 * network infrastructure. The fault is registered before {@code inject} returns,
 * so the rollback obligation exists before activation is reported successful.
 * Rollback is idempotent, and expired faults stop matching requests even if
 * rollback has not run yet.
 */
public final class LocalFaultProvider implements FaultProvider {

    /** Adds a fixed delay before each matching request. Params: {@code delayMs}. */
    public static final String HTTP_LATENCY = "http-latency";
    /** Fails each matching request before it reaches the target. Params: none required. */
    public static final String HTTP_ERROR = "http-error";
    /** Delivers each matching request but discards the response. Params: none. */
    public static final String HTTP_RESPONSE_LOSS = "http-response-loss";

    /** Scope value matching every target. */
    public static final String ALL_TARGETS = "*";

    static final long MAX_LATENCY_MS = 60_000;

    private static final Set<String> CAPABILITIES =
            Set.of(HTTP_LATENCY, HTTP_ERROR, HTTP_RESPONSE_LOSS);

    record RegisteredFault(long sequence, ActiveFault fault, Map<String, Object> params) {}

    private final ConcurrentMap<String, RegisteredFault> active = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
        if (!CAPABILITIES.contains(faultType)) {
            throw new IllegalArgumentException("Unsupported fault type: " + faultType);
        }
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        if (HTTP_LATENCY.equals(faultType)) {
            long delayMs = toLong(safeParams.get("delayMs"));
            if (delayMs <= 0 || delayMs > MAX_LATENCY_MS) {
                throw new IllegalArgumentException(
                        "http-latency requires delayMs in [1, " + MAX_LATENCY_MS + "], got: "
                                + safeParams.get("delayMs"));
            }
        }

        long seq = sequence.incrementAndGet();
        String scope = context.targetScope() == null || context.targetScope().isBlank()
                ? ALL_TARGETS : context.targetScope();
        // Clamp activation below the expiry: for millisecond-scale fault
        // durations the clock may already have reached hardExpiryMs.
        long activatedAtMs = Math.min(System.currentTimeMillis(), context.hardExpiryMs() - 1);
        ActiveFault fault = new ActiveFault(
                faultType + "-" + seq, faultType, scope,
                activatedAtMs, context.hardExpiryMs(),
                "deregister in-process fault; requests are no longer intercepted");
        active.put(fault.handle(), new RegisteredFault(seq, fault, safeParams));
        return fault;
    }

    @Override
    public void rollback(ActiveFault fault, FaultContext context) {
        active.remove(fault.handle());
    }

    /**
     * Faults currently applying to the given target, in activation order.
     * Expired faults are excluded even before rollback runs.
     */
    List<RegisteredFault> activeFaultsFor(String targetId, long nowMs) {
        List<RegisteredFault> matching = new ArrayList<>();
        for (RegisteredFault registered : active.values()) {
            ActiveFault fault = registered.fault();
            boolean scopeMatches = ALL_TARGETS.equals(fault.targetScope())
                    || fault.targetScope().equals(targetId);
            if (scopeMatches && nowMs < fault.hardExpiryMs()) {
                matching.add(registered);
            }
        }
        matching.sort(Comparator.comparingLong(RegisteredFault::sequence));
        return matching;
    }

    /** Number of faults still registered (for diagnostics and tests). */
    public int activeCount() {
        return active.size();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
