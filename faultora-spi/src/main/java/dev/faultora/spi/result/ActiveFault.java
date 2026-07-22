package dev.faultora.spi.result;

/**
 * An active fault that has been injected into the target system.
 * Every ActiveFault must have a rollback plan and hard expiry.
 *
 * @param handle         unique handle for this fault instance
 * @param faultType      type identifier (e.g. "http-latency", "connection-reset")
 * @param targetScope    target the fault applies to
 * @param activatedAtMs  epoch millis when the fault was activated
 * @param hardExpiryMs   absolute deadline (epoch millis) for automatic rollback
 * @param rollbackDesc   human-readable description of the rollback action
 */
public record ActiveFault(
        String handle,
        String faultType,
        String targetScope,
        long activatedAtMs,
        long hardExpiryMs,
        String rollbackDesc
) {
    public ActiveFault {
        if (handle == null || handle.isBlank()) throw new IllegalArgumentException("handle must not be blank");
        if (hardExpiryMs <= activatedAtMs) throw new IllegalArgumentException("hardExpiryMs must be after activatedAtMs");
    }
}
