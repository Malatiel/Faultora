package dev.faultora.spi.contract;

import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.result.ActiveFault;

import java.util.Set;

/**
 * Provider for injecting controlled faults into target systems.
 * Every fault has a hard expiry and mandatory rollback.
 */
public interface FaultProvider {

    /**
     * Fault capabilities this provider supports (e.g. "http-latency", "connection-reset").
     */
    Set<String> capabilities();

    /**
     * Inject a fault into the target system.
     * The returned ActiveFault MUST be rollback-safe: the provider guarantees
     * that rollback() can be called even if inject() partially succeeded.
     *
     * @param faultType type of fault to inject
     * @param params    fault-specific parameters
     * @param context   fault context with target scope and hard expiry
     * @return active fault handle with rollback description
     */
    ActiveFault inject(String faultType, java.util.Map<String, Object> params, FaultContext context);

    /**
     * Roll back an active fault.
     * Must be idempotent: calling rollback on an already-rolled-back fault is a no-op.
     *
     * @param fault   the active fault to roll back
     * @param context fault context
     */
    void rollback(ActiveFault fault, FaultContext context);
}
