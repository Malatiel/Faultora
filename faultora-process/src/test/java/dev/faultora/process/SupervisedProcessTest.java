package dev.faultora.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisedProcessTest {
    private static ProcessBuilder child(String mode) {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Peer.class.getName(), mode);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        return builder;
    }

    @Test
    void blockedReadAndBlockedWriteBothEndWithTheirChild() throws Exception {
        for (boolean writing : new boolean[] {false, true}) {
            try (var owned = new SupervisedProcess(child("sleep"), 10000, 100, 100, 1024)) {
                assertThatThrownBy(() -> owned.call(() -> {
                    if (writing) { owned.process().getOutputStream().write(new byte[1024 * 1024]); return 0; }
                    return owned.process().getInputStream().read();
                }, Duration.ofMillis(150))).isInstanceOf(IOException.class).hasMessage("DEADLINE_EXCEEDED");
                assertThat(owned.process().isAlive()).isFalse();
                assertThat(owned.cleanupConfirmed()).isTrue();
            }
        }
    }

    @Test
    void stderrFloodIsCountedAndRefusedWithoutExportingItsContents() throws Exception {
        try (var owned = new SupervisedProcess(child("stderr"), 10000, 100, 100, 128)) {
            assertThatThrownBy(() -> owned.call(() -> owned.process().getInputStream().read(), Duration.ofSeconds(5)))
                    .hasMessage("STDERR_LIMIT_EXCEEDED").hasNoCause();
            assertThat(owned.process().isAlive()).isFalse();
        }
    }

    @Test
    void interruptionKillsOwnedWorkBeforeItIsReported() throws Exception {
        try (var owned = new SupervisedProcess(child("sleep"), 10000, 100, 100, 1024)) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> owned.call(() -> owned.process().getInputStream().read(), Duration.ofSeconds(5)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(owned.process().isAlive()).isFalse();
        } finally { Thread.interrupted(); }
    }

    @Test
    void anIdleForgottenPeerCannotOutliveTheAbsoluteLifecycle() throws Exception {
        try (var owned = new SupervisedProcess(child("sleep"), 100, 50, 100, 1024)) {
            assertThat(owned.process().waitFor(3, TimeUnit.SECONDS)).isTrue();
            assertThat(owned.process().exitValue()).isNotZero();
        }
    }

    @Test
    void aNormalExitIsConfirmed() throws Exception {
        try (var owned = new SupervisedProcess(child("exit"), 10000, 100, 100, 1024)) {
            owned.awaitExit(Duration.ofSeconds(5));
            assertThat(owned.cleanupConfirmed()).isTrue();
        }
    }

    @Test
    void aCompletedFutureAtTheDeadlineIsStillLate() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        try (var owned = new SupervisedProcess(child("sleep"), 10000, 100, 100, 1024, clock::get)) {
            assertThatThrownBy(() -> owned.call(() -> {
                clock.set(TimeUnit.MILLISECONDS.toNanos(100));
                return "too late";
            }, Duration.ofMillis(100))).hasMessage("DEADLINE_EXCEEDED");
            assertThat(owned.process().isAlive()).isFalse();
        }
    }

    public static final class Peer {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("exit")) return;
            if (args[0].equals("stderr")) System.err.write("SYNTHETIC_PRIVATE_STDERR".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Thread.sleep(600000);
        }
    }
}
