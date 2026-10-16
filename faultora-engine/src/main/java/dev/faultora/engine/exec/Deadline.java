package dev.faultora.engine.exec;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Immutable monotonic budget shared by a run and narrowed by its children. */
public final class Deadline {
    private static final Sleeper SYSTEM_SLEEP = TimeUnit.NANOSECONDS::sleep;
    private static final Deadline NONE = new Deadline(System::nanoTime, SYSTEM_SLEEP, 0, -1);
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final long start;
    private final long budget;

    private Deadline(LongSupplier clock, Sleeper sleeper, long start, long budget) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.start = start;
        this.budget = budget;
    }

    public static Deadline none() {
        return NONE;
    }

    /** A nonpositive configured timeout adds no deadline. */
    public static Deadline start(long timeoutMs) {
        return start(timeoutMs, System::nanoTime);
    }

    static Deadline start(long timeoutMs, LongSupplier clock) {
        return start(timeoutMs, clock, SYSTEM_SLEEP);
    }

    static Deadline start(long timeoutMs, LongSupplier clock, Sleeper sleeper) {
        return timeoutMs > 0
                ? new Deadline(clock, sleeper, clock.getAsLong(), TimeUnit.MILLISECONDS.toNanos(timeoutMs))
                : new Deadline(clock, sleeper, 0, -1);
    }

    public Deadline limit(long timeoutMs) {
        if (timeoutMs <= 0) {
            return this;
        }
        long now = clock.getAsLong();
        return new Deadline(clock, sleeper, now,
                Math.min(remainingNanos(now), TimeUnit.MILLISECONDS.toNanos(timeoutMs)));
    }

    public long remainingNanos() {
        return remainingNanos(clock.getAsLong());
    }

    private long remainingNanos(long now) {
        return budget < 0 ? Long.MAX_VALUE : Math.max(0, budget - (now - start));
    }

    /** Round down: a fractional millisecond must not become an unbounded timeout. */
    public long remainingMillis() {
        return budget < 0 ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(remainingNanos());
    }

    public boolean expired() {
        return remainingMillis() == 0;
    }

    void pause(long nanos) throws InterruptedException {
        sleeper.sleep(nanos);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }
}
