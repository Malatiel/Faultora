package dev.faultora.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedProcessTest {
    @TempDir
    Path directory;

    @Test
    void aChildThatOutlivesItsTimeoutIsKilledBeforeTheFailureIsReported() throws Exception {
        Path sleeper = Files.writeString(directory.resolve("Sleeper.java"),
                "class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), sleeper.toString()));
        AtomicReference<Process> child = new AtomicReference<>();

        assertThatThrownBy(() -> BoundedProcess.run(builder, Duration.ofSeconds(3), child::set))
                .isInstanceOf(AssertionError.class).hasMessageContaining("did not finish");

        assertThat(child.get()).isNotNull();
        assertThat(child.get().isAlive()).as("no orphan JVM left behind").isFalse();
        assertThat(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive)).isNotEqualTo(java.util.Optional.of(true));
    }

    @Test
    void aChildThatFinishesReportsItsExitCode() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version"));
        assertThat(BoundedProcess.run(builder.redirectErrorStream(true), Duration.ofSeconds(60), process -> { }))
                .isZero();
    }
}
