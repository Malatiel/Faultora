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
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!cancellation.get()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            long sleepMs = Math.max(
                    1, Math.min(SLICE_MS, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMs);
        }
    }
}
