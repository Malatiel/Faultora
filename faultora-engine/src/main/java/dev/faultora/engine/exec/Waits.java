package dev.faultora.engine.exec;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellable waiting.
 * <p>
 * Every pause in a run — wait steps, retry backoff, poll intervals — goes
 * through here, so cancellation is observed within a bounded slice instead of
 * being discovered only after a long sleep returns.
 */
public final class Waits {

    /** Longest uninterrupted sleep; bounds how late cancellation is noticed. */
    private static final long SLICE_MS = 100;

    private Waits() {
    }

    public static void sleep(long waitMs, AtomicBoolean cancellation) throws InterruptedException {
        sleep(waitMs, cancellation, Deadline.none());
    }

    public static void sleep(long waitMs, AtomicBoolean cancellation, Deadline parent)
            throws InterruptedException {
        long start = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
        while (!cancellation.get()) {
            long remainingNanos = Math.min(budget - (System.nanoTime() - start), parent.remainingNanos());
            if (remainingNanos <= 0) return;
            TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(SLICE_MS), remainingNanos));
        }
    }
}
