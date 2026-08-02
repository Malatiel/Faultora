package dev.faultora.runner.protocol;

/**
 * A run's permission to exist, with an expiry.
 * <p>
 * The lease is what makes "disconnection cannot extend a run" true without
 * anything having to say so: the runner may execute while the lease holds, and
 * when it can no longer renew it, it stops. Enforcement is local because the
 * case it exists for is the case where nothing can be heard.
 * <p>
 * It does not add a clock. The bound on a run is the smallest of its lease, its
 * scenario deadline and the policy's budget, and an expiring lease drives the
 * cancellation the engine already has — the same path a scenario deadline
 * takes. A second stop mechanism could disagree with the fault watchdog about
 * when a fault ends, and a fault outliving its run is the worst version of that
 * disagreement. ADR-020 records it.
 *
 * @param expiresAtEpochMs when this permission ends, in epoch milliseconds
 * @param renewEveryMs     how often the runner should try to renew, well inside
 *                         the expiry so a lost heartbeat is survivable
 */
public record Lease(long expiresAtEpochMs, long renewEveryMs) {

    public Lease {
        if (expiresAtEpochMs <= 0) {
            throw new IllegalArgumentException("A lease without an expiry is not a lease");
        }
        if (renewEveryMs <= 0 || renewEveryMs >= expiresAtEpochMs) {
            throw new IllegalArgumentException(
                    "A renewal interval must be positive and shorter than the lease");
        }
    }

    /** Whether this lease still permits work at the given moment. */
    public boolean holdsAt(long nowEpochMs) {
        return nowEpochMs < expiresAtEpochMs;
    }

    /** How long is left, never negative. */
    public long remainingMs(long nowEpochMs) {
        return Math.max(0, expiresAtEpochMs - nowEpochMs);
    }

    /** The same lease, extended to a new expiry. */
    public Lease renewedUntil(long newExpiryEpochMs) {
        return new Lease(newExpiryEpochMs, renewEveryMs);
    }
}
