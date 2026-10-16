package dev.faultora.engine.exec;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A test clock whose waits advance the same monotonic time as its deadlines. */
public final class ManualTime {
    private final AtomicLong nanos = new AtomicLong();

    public Deadline deadline(long timeoutMs) {
        return Deadline.start(timeoutMs, nanos::get, duration -> {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            nanos.addAndGet(duration);
        });
    }

    public void advanceMillis(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(duration));
    }

    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(nanos.get());
    }
}
