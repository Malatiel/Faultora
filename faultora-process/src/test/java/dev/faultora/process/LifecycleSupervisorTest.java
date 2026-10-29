package dev.faultora.process;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleSupervisorTest {
    @Test
    void completionDoesNotCancelOrTerminateOwnedWork() throws Exception {
        var cancellation = new AtomicBoolean();
        var terminated = new CountDownLatch(1);
        var supervisor = new LifecycleSupervisor(cancellation, 30, 30, 40,
                () -> { throw new AssertionError("unexpected marker"); }, () -> { }, terminated::countDown);
        supervisor.start();
        supervisor.complete();
        assertThat(terminated.await(150, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(cancellation).isFalse();
        assertThat(supervisor.unconfirmed()).isFalse();
    }

    @Test
    void aBlockedMarkerCannotExtendTheHardBoundOrBeReplacedByLateCompletion() throws Exception {
        var cancellation = new AtomicBoolean();
        var releaseMarker = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var terminated = new CountDownLatch(1);
        var supervisor = new LifecycleSupervisor(cancellation, 30, 30, 100,
                () -> { entered.countDown(); releaseMarker.await(); }, () -> { }, terminated::countDown);
        try {
            supervisor.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(terminated.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation).isTrue();
            supervisor.complete();
            assertThat(supervisor.unconfirmed()).isTrue();
        } finally { releaseMarker.countDown(); }
    }

    @Test
    void failedMarkerStillReachesTermination() throws Exception {
        var reported = new AtomicBoolean();
        var terminated = new CountDownLatch(1);
        var supervisor = new LifecycleSupervisor(new AtomicBoolean(), 30, 30, 40,
                () -> { throw new java.io.IOException("SYNTHETIC_PRIVATE_IO"); },
                () -> reported.set(true), terminated::countDown);
        supervisor.start();
        assertThat(terminated.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(reported).isTrue();
        assertThat(supervisor.unconfirmed()).isTrue();
    }
}
