package dev.faultora.engine.exec;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineTest {
    @Test
    void childrenCannotRestartOrWidenTheirParentsBudget() {
        AtomicLong clock = new AtomicLong();
        Deadline parent = Deadline.start(100, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
        Deadline child = parent.limit(1000);
        assertThat(child.remainingMillis()).isEqualTo(70);

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        assertThat(parent.remainingMillis()).isEqualTo(50);
        assertThat(child.remainingMillis()).isEqualTo(50);
        Deadline tighter = child.limit(10);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        assertThat(tighter.expired()).isTrue();
        assertThat(child.remainingMillis()).isEqualTo(40);
        assertThat(tighter.limit(1000).expired()).isTrue();
    }

    @Test
    void aFractionalMillisecondIsNotPassedAsAnUnlimitedTimeout() {
        AtomicLong clock = new AtomicLong();
        Deadline deadline = Deadline.start(1, clock::get);
        clock.set(999_999);
        assertThat(deadline.remainingMillis()).isZero();
        assertThat(deadline.expired()).isTrue();
    }

    @Test
    void aLargeBudgetAndNanoTimeWraparoundRemainPositive() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
        Deadline deadline = Deadline.start(Long.MAX_VALUE, clock::get);
        clock.addAndGet(10);
        assertThat(deadline.remainingNanos()).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(deadline.expired()).isFalse();
    }

    @Test
    void anAbsentConfiguredDeadlineDoesNotWidenAnExistingOne() {
        AtomicLong clock = new AtomicLong();
        Deadline deadline = Deadline.start(50, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        assertThat(deadline.limit(0).remainingMillis()).isEqualTo(40);
        assertThat(deadline.limit(-1).remainingMillis()).isEqualTo(40);
        assertThat(Deadline.none().remainingMillis()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void anUnrepresentablyLongWaitCanStillBeCancelled() throws Exception {
        AtomicBoolean cancellation = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = workers.submit(() -> {
                entered.countDown();
                Waits.sleep(Long.MAX_VALUE, cancellation);
                return true;
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            cancellation.set(true);
            assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
        }
    }
}
