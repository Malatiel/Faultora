package dev.faultora.engine.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitsTest {
    @Test
    void waitsAndTheirParentsUseTheSameClockAndBoundEverySleepSlice() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<Long> slices = new ArrayList<>();
        Deadline parent = Deadline.start(500, clock::get, nanos -> {
            slices.add(TimeUnit.NANOSECONDS.toMillis(nanos));
            clock.addAndGet(nanos);
        });

        Waits.sleep(250, new AtomicBoolean(), parent);

        assertThat(slices).containsExactly(100L, 100L, 50L);
        assertThat(parent.remainingMillis()).isEqualTo(250);
    }

    @Test
    void aParentBudgetClipsTheWaitWithoutAcquiringAnotherBudget() throws Exception {
        ManualTime time = new ManualTime();
        Deadline parent = time.deadline(75);
        time.advanceMillis(50);

        Waits.sleep(1000, new AtomicBoolean(), parent);

        assertThat(time.elapsedMillis()).isEqualTo(75);
        assertThat(parent.expired()).isTrue();
    }

    @Test
    void anExpiredParentDoesNotSleep() throws Exception {
        ManualTime time = new ManualTime();
        Deadline parent = time.deadline(10);
        time.advanceMillis(10);

        Waits.sleep(1000, new AtomicBoolean(), parent);

        assertThat(time.elapsedMillis()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aNonpositiveWaitDoesNotInheritItsParentsRemainingDelay(long wait) throws Exception {
        ManualTime time = new ManualTime();

        Waits.sleep(wait, new AtomicBoolean(), time.deadline(100));

        assertThat(time.elapsedMillis()).isZero();
    }

    @Test
    void anUnlimitedParentStillLimitsTheWaitToItsOwnDuration() throws Exception {
        ManualTime time = new ManualTime();

        Waits.sleep(250, new AtomicBoolean(), time.deadline(0));

        assertThat(time.elapsedMillis()).isEqualTo(250);
    }

    @Test
    void cancellationBeforeEntryDoesNotSleep() throws Exception {
        ManualTime time = new ManualTime();

        Waits.sleep(1000, new AtomicBoolean(true), time.deadline(5000));

        assertThat(time.elapsedMillis()).isZero();
    }

    @Test
    void cancellationDuringTheFirstSlicePreventsAnotherSlice() throws Exception {
        AtomicBoolean cancellation = new AtomicBoolean();
        AtomicLong clock = new AtomicLong();
        Deadline parent = Deadline.start(5000, clock::get, nanos -> {
            clock.addAndGet(nanos);
            cancellation.set(true);
        });

        Waits.sleep(1000, cancellation, parent);

        assertThat(TimeUnit.NANOSECONDS.toMillis(clock.get())).isEqualTo(100);
    }

    @Test
    void interruptionStopsTheWaitBeforeTimeAdvances() {
        ManualTime time = new ManualTime();
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> Waits.sleep(1000, new AtomicBoolean(), time.deadline(5000)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(time.elapsedMillis()).isZero();
        } finally {
            Thread.interrupted();
        }
    }
}
