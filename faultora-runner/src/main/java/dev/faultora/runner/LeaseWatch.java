package dev.faultora.runner;

import dev.faultora.runner.protocol.Lease;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a lease into the cancellation the engine already understands.
 * <p>
 * This is the release gate's second half — <em>disconnection cannot extend a
 * run or an active fault beyond policy</em> — and the whole of how it is met.
 * The engine takes an {@link AtomicBoolean} it checks between nodes; a lease
 * that has run out sets it. Nothing new stops the run, the fault watchdog is
 * untouched, and the path from "the lease expired" to "the faults are rolled
 * back and cleanup ran" is the path a scenario deadline already takes.
 * <p>
 * Two properties are deliberate:
 * <ul>
 *   <li><b>It needs no network.</b> The case a lease exists for is the case
 *       where nothing can be heard, so enforcement that required a message
 *       would be enforcement that is absent exactly when it is needed.</li>
 *   <li><b>It runs on the runner's clock.</b> The deadline is the moment the
 *       dispatch was received plus the lease's lifetime, never the
 *       dispatcher's absolute expiry — a bound a disagreeing clock can widen
 *       is not a bound.</li>
 * </ul>
 * A renewal arrives as a lease the dispatcher granted; this class never extends
 * itself, because a lease a runner can extend is a comment.
 */
public final class LeaseWatch implements AutoCloseable {

    /** How often the deadline is compared to the clock. */
    private static final Duration TICK = Duration.ofMillis(100);

    private final AtomicBoolean cancellation;
    private final AtomicLong deadlineMs;
    private final AtomicLong renewEveryMs;
    private final AtomicBoolean expired = new AtomicBoolean();
    private volatile boolean watching;
    private Thread watcher;

    /**
     * @param lease        the permission this run holds
     * @param receivedAtMs when the runner received it, on the runner's clock
     * @param cancellation the flag the engine is executing against
     */
    public LeaseWatch(Lease lease, long receivedAtMs, AtomicBoolean cancellation) {
        this.cancellation = cancellation;
        this.deadlineMs = new AtomicLong(lease.deadlineFrom(receivedAtMs));
        this.renewEveryMs = new AtomicLong(lease.renewEveryMs());
    }

    /** Start watching. */
    public void start() {
        watching = true;
        watcher = new Thread(this::watchUntilStopped, "lease-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watchUntilStopped() {
        while (watching) {
            if (System.currentTimeMillis() >= deadlineMs.get()) {
                expire();
                return;
            }
            try {
                Thread.sleep(TICK.toMillis());
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Extend to a lease the dispatcher granted.
     * <p>
     * Ignored once the run has already been cancelled: a lease that arrives
     * after the runner stopped cannot un-stop it, and rolling a cancellation
     * back would leave the faults it rolled back already gone.
     */
    public void renew(Lease renewed, long receivedAtMs) {
        if (!expired.get()) {
            deadlineMs.set(renewed.deadlineFrom(receivedAtMs));
            renewEveryMs.set(renewed.renewEveryMs());
        }
    }

    /**
     * How often the granting side asked to be asked again.
     * <p>
     * It comes from the lease for the same reason the deadline does. A runner
     * that picked its own interval would be a second rule beside the one the
     * protocol already carries, and the two would be free to disagree — about
     * how many renewals fit inside a lease, which is the whole of whether a
     * lost heartbeat is survivable.
     */
    public long renewEveryMs() {
        return renewEveryMs.get();
    }

    /** Whether the lease ran out while this was watching. */
    public boolean hasExpired() {
        return expired.get();
    }

    /** How long the run has left, on the runner's clock. */
    public long remainingMs() {
        return Math.max(0, deadlineMs.get() - System.currentTimeMillis());
    }

    private void expire() {
        if (expired.compareAndSet(false, true)) {
            cancellation.set(true);
        }
    }

    @Override
    public void close() {
        watching = false;
        if (watcher != null) {
            watcher.interrupt();
        }
    }
}
