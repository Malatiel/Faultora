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
 * <p>
 * <b>A lease travels as an issue time and a lifetime, not as an absolute
 * expiry.</b> That is two decisions in one shape:
 * <ul>
 *   <li>the invariant "a renewal must fit inside the lease" becomes checkable.
 *       Against an absolute expiry it was not: comparing an interval of
 *       seconds with an epoch millisecond is a comparison that can never be
 *       true, so a five-second lease renewed every minute was accepted in
 *       silence — a stated bound that did not hold, in the module whose whole
 *       subject is bounds that do;</li>
 *   <li>the runner can bound the run <em>by its own clock</em>. It stops at
 *       {@link #deadlineFrom} — the moment it received the dispatch plus the
 *       lifetime — so a runner whose clock disagrees with the dispatcher's
 *       cannot be talked into running longer than the lifetime it was granted.
 *       An absolute expiry read on a skewed clock is exactly the extension
 *       this is supposed to prevent.</li>
 * </ul>
 *
 * @param issuedAtEpochMs when the dispatcher granted this, on the dispatcher's
 *                        clock
 * @param ttlMs           how long the permission lasts from that moment
 * @param renewEveryMs    how often the runner should try to renew, shorter than
 *                        the lifetime so a lost heartbeat is survivable
 */
public record Lease(long issuedAtEpochMs, long ttlMs, long renewEveryMs) {

    public Lease {
        if (issuedAtEpochMs <= 0) {
            throw new IllegalArgumentException("A lease has to say when it was granted");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("A lease without a lifetime is not a lease");
        }
        if (renewEveryMs <= 0 || renewEveryMs >= ttlMs) {
            throw new IllegalArgumentException(
                    "A lease of " + ttlMs + "ms cannot be renewed every " + renewEveryMs
                            + "ms: the renewal has to happen while the lease still holds");
        }
    }

    /**
     * When this permission ends on the dispatcher's clock.
     * <p>
     * The granting side's view, and the one it uses to decide a run is gone.
     * The runner uses {@link #deadlineFrom} instead.
     */
    public long expiresAtEpochMs() {
        return issuedAtEpochMs + ttlMs;
    }

    /**
     * When this permission ends on the clock of whoever received it.
     * <p>
     * This is what the runner stops at. It depends on no agreement between two
     * hosts about the time, which is the point: a lease is a bound, and a bound
     * that a wrong clock can widen is not one.
     */
    public long deadlineFrom(long receivedAtEpochMs) {
        return receivedAtEpochMs + ttlMs;
    }

    /** Whether this lease still permits work, on the granting side's clock. */
    public boolean holdsAt(long nowEpochMs) {
        return nowEpochMs < expiresAtEpochMs();
    }

    /** How long is left on the granting side's clock, never negative. */
    public long remainingMs(long nowEpochMs) {
        return Math.max(0, expiresAtEpochMs() - nowEpochMs);
    }

    /**
     * The same permission, granted again from a new moment.
     * <p>
     * This belongs to the side that can revoke it. A runner extending its own
     * lease would make the lease a comment, which is why nothing on the runner
     * calls this — a renewal arrives as a lease the dispatcher granted.
     */
    public Lease renewedAt(long nowEpochMs) {
        return new Lease(nowEpochMs, ttlMs, renewEveryMs);
    }
}
