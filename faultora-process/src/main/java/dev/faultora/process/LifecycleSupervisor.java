package dev.faultora.process;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One monotonic lifecycle limit, shared by current-process and child-process owners. */
public final class LifecycleSupervisor {
    @FunctionalInterface
    public interface Marker { void write() throws Exception; }

    private static final int RUNNING = 0;
    private static final int COMPLETED = 1;
    private static final int UNCONFIRMED = 2;
    private final AtomicBoolean cancellation;
    private final Marker marker;
    private final Runnable markerFailed;
    private final Runnable terminate;
    private final CountDownLatch completed = new CountDownLatch(1);
    private final CountDownLatch markerDone = new CountDownLatch(1);
    private final AtomicInteger state = new AtomicInteger();
    private final AtomicBoolean startedGuard = new AtomicBoolean();
    private final long started = System.nanoTime();
    private final long runNanos;
    private final long markerNanos;
    private final long haltNanos;

    /** Actions are trusted owner operations, never provider callbacks; marker I/O runs on a daemon. */
    public LifecycleSupervisor(AtomicBoolean cancellation, long runMs, long cleanupMs, long graceMs,
                               Marker marker, Runnable markerFailed, Runnable terminate) {
        if (runMs <= 0 || cleanupMs <= 0 || graceMs < 2) {
            throw new IllegalArgumentException("Lifecycle budgets must be positive");
        }
        this.cancellation = java.util.Objects.requireNonNull(cancellation);
        this.marker = java.util.Objects.requireNonNull(marker);
        this.markerFailed = java.util.Objects.requireNonNull(markerFailed);
        this.terminate = java.util.Objects.requireNonNull(terminate);
        runNanos = Duration.ofMillis(runMs).toNanos();
        long totalMs = Math.addExact(Math.addExact(runMs, cleanupMs), graceMs);
        haltNanos = Duration.ofMillis(totalMs).toNanos();
        markerNanos = Duration.ofMillis(totalMs - Math.min(1000, graceMs / 2)).toNanos();
    }

    public void start() {
        if (!startedGuard.compareAndSet(false, true)) throw new IllegalStateException("Already supervised");
        Thread guard = new Thread(() -> {
            if (await(completed, runNanos)) return;
            cancellation.set(true);
            if (!await(completed, markerNanos)) terminate();
        }, "faultora-lifecycle-deadline");
        guard.setDaemon(true);
        guard.start();
    }

    public void complete() {
        if (state.compareAndSet(RUNNING, COMPLETED)) completed.countDown();
    }

    public boolean unconfirmed() { return state.get() == UNCONFIRMED; }

    public void join(Thread worker) throws InterruptedException {
        while (worker.isAlive()) {
            long remaining = markerNanos - (System.nanoTime() - started);
            if (remaining <= 0) { terminate(); return; }
            TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
        }
    }

    private boolean await(CountDownLatch latch, long until) {
        while (true) {
            long remaining = until - (System.nanoTime() - started);
            if (remaining <= 0) return latch.getCount() == 0;
            try { return latch.await(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException ignored) { /* An interrupt never extends ownership. */ }
        }
    }

    private void terminate() {
        try {
            if (state.compareAndSet(RUNNING, UNCONFIRMED)) {
                cancellation.set(true);
                Thread writer = new Thread(() -> {
                    try { marker.write(); }
                    catch (Exception unavailable) { markerFailed.run(); }
                    finally { markerDone.countDown(); }
                }, "faultora-termination-marker");
                writer.setDaemon(true);
                writer.start();
            }
        } finally {
            if (unconfirmed()) {
                await(markerDone, haltNanos);
                terminate.run();
            }
        }
    }
}
