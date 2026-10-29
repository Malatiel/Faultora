package dev.faultora.integration;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A child process that cannot outlive the wait for it, whatever ends that wait. */
final class BoundedProcess {
    private static final Duration TEARDOWN = Duration.ofSeconds(10);

    private BoundedProcess() {
    }

    /**
     * Start the process and wait for it; on a timeout, an interrupt or any
     * failure while waiting, kill it and its descendants and confirm it exited.
     *
     * @param started told the process once it is running
     * @return the exit code
     * @throws AssertionError when the process did not finish in {@code timeout}
     */
    static int run(ProcessBuilder builder, Duration timeout, Consumer<Process> started)
            throws IOException, InterruptedException {
        Process process = builder.start();
        try {
            started.accept(process);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Process did not finish within " + timeout + ": " + builder.command());
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(TEARDOWN.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Process " + process.pid() + " did not stop after being killed");
                }
            }
        }
    }
}
