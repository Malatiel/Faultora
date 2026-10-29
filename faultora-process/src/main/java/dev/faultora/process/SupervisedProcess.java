package dev.faultora.process;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Owned child and bounded I/O work. Callers provide trusted codec operations, never plugin callbacks. */
public final class SupervisedProcess implements AutoCloseable {
    private final Process process;
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "faultora-child-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LifecycleSupervisor lifecycle;
    private final LongSupplier clock;
    private final long started;
    private final long runNanos;
    private volatile String failure;
    private volatile boolean cleanupConfirmed;

    public SupervisedProcess(ProcessBuilder builder, long runMs, long cleanupMs, long graceMs,
                             int maxStderrBytes) throws IOException {
        this(builder, runMs, cleanupMs, graceMs, maxStderrBytes, System::nanoTime);
    }

    SupervisedProcess(ProcessBuilder builder, long runMs, long cleanupMs, long graceMs,
                      int maxStderrBytes, LongSupplier clock) throws IOException {
        if (runMs <= 0 || cleanupMs <= 0 || graceMs < 2 || maxStderrBytes < 0) {
            throw new IllegalArgumentException("Invalid child lifecycle limits");
        }
        runNanos = Duration.ofMillis(runMs).toNanos();
        this.clock = java.util.Objects.requireNonNull(clock);
        started = clock.getAsLong();
        // Validate all deadline arithmetic before creating an owned process.
        Duration.ofMillis(Math.addExact(Math.addExact(runMs, cleanupMs), graceMs)).toNanos();
        process = builder.start();
        lifecycle = new LifecycleSupervisor(cancellation, runMs, cleanupMs, graceMs,
                () -> failure = "DEADLINE_EXCEEDED", () -> { }, this::forceStop);
        try {
            Thread stderr = new Thread(() -> drainStderr(maxStderrBytes), "faultora-child-stderr");
            stderr.setDaemon(true);
            stderr.start();
            Thread exit = new Thread(() -> {
                try { process.waitFor(); lifecycle.complete(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "faultora-child-exit");
            exit.setDaemon(true);
            exit.start();
            lifecycle.start();
        } catch (RuntimeException | Error startupFailed) {
            forceStop();
            io.shutdownNow();
            throw startupFailed;
        }
    }

    public Process process() { return process; }
    public boolean cleanupConfirmed() { return cleanupConfirmed; }
    public long remainingMillis() {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, runNanos - (clock.getAsLong() - started)));
    }

    /** One pending call. The local budget cannot extend the absolute process deadline. */
    public <T> T call(Callable<T> action, Duration timeout) throws IOException, InterruptedException {
        if (closed.get() || failure != null) throw new IOException("PROCESS_CLOSED");
        if (!busy.compareAndSet(false, true)) throw new IOException("PROCESS_BUSY");
        Future<T> pending = null;
        try {
            long invoked = clock.getAsLong();
            long remaining = Math.min(timeout.toNanos(), runNanos - (invoked - started));
            if (remaining <= 0 || cancellation.get()) throw new IOException("DEADLINE_EXCEEDED");
            pending = io.submit(action);
            T value = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (clock.getAsLong() - invoked >= remaining) failure = "DEADLINE_EXCEEDED";
            if (failure != null) throw new IOException(failure);
            return value;
        } catch (TimeoutException timedOut) {
            failure = "DEADLINE_EXCEEDED";
            throw new IOException(failure);
        } catch (ExecutionException failed) {
            if (failure != null) throw new IOException(failure);
            failure = "PROCESS_CALL_FAILED";
            if (failed.getCause() instanceof IOException known) throw known;
            throw new IOException("PROCESS_CALL_FAILED");
        } catch (InterruptedException interrupted) {
            failure = "CANCELLED";
            throw interrupted;
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (failure != null || (pending != null && pending.isCancelled())) forceStop();
            busy.set(false);
        }
    }

    /** A protocol acknowledgement alone is not proof that the peer exited. */
    public void awaitExit(Duration timeout) throws IOException, InterruptedException {
        try {
            if (!process.waitFor(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                failure = "SHUTDOWN_DEADLINE_EXCEEDED";
                forceStop();
                throw new IOException(failure);
            }
        } catch (InterruptedException interrupted) {
            failure = "CANCELLED";
            forceStop();
            throw interrupted;
        }
        cleanupConfirmed = true;
        lifecycle.complete();
        if (process.exitValue() != 0) throw new IOException("PROCESS_EXIT_FAILED");
    }

    private void drainStderr(int maximum) {
        byte[] bytes = new byte[4096];
        long count = 0;
        try {
            int read;
            while ((read = process.getErrorStream().read(bytes, 0,
                    (int) Math.min(bytes.length, maximum - count + 1))) >= 0) {
                count += read;
                if (count > maximum) {
                    failure = "STDERR_LIMIT_EXCEEDED";
                    forceStop();
                    return;
                }
            }
        } catch (IOException closedPipe) {
            // Stderr bytes are deliberately never retained or exported.
        }
    }

    private synchronized void forceStop() {
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            while (process.isAlive() && System.nanoTime() < end) {
                try { process.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException stop) { interrupted = true; }
            }
            cleanupConfirmed = !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
            if (cleanupConfirmed) lifecycle.complete();
            io.shutdownNow();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        forceStop();
        io.shutdownNow();
        if (!cleanupConfirmed) throw new IOException("PROCESS_CLEANUP_UNCONFIRMED");
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
    }
}
