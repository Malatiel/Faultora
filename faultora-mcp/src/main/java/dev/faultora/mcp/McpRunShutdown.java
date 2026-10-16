package dev.faultora.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One absolute lifecycle limit, independent of connector cooperation and shutdown hooks. */
final class McpRunShutdown {
    static final long GRACE_MS = 5000;
    static final int EXIT_UNCONFIRMED = 3;
    static final String REASON = "SHUTDOWN_DEADLINE_EXCEEDED";
    private static final int RUNNING = 0;
    private static final int COMPLETED = 1;
    private static final int UNCONFIRMED = 2;
    private final String runId;
    private final Path directory;
    private final AtomicBoolean cancellation;
    private final CountDownLatch completed = new CountDownLatch(1);
    private final CountDownLatch markerDone = new CountDownLatch(1);
    // Completion and forced termination compete once; late completion cannot undo termination.
    private final AtomicInteger state = new AtomicInteger();
    private final long started = System.nanoTime();
    private final long runNanos;
    private final long markerNanos;
    private final long haltNanos;

    McpRunShutdown(String runId, Path directory, AtomicBoolean cancellation,
                   long runMs, long cleanupMs, long graceMs) {
        if (runMs <= 0 || cleanupMs <= 0 || graceMs < 2) {
            throw new IllegalArgumentException("Lifecycle budgets must be positive");
        }
        this.runId = runId;
        this.directory = directory;
        this.cancellation = cancellation;
        runNanos = java.time.Duration.ofMillis(runMs).toNanos();
        long totalMs = Math.addExact(Math.addExact(runMs, cleanupMs), graceMs);
        haltNanos = java.time.Duration.ofMillis(totalMs).toNanos();
        long markerMs = Math.min(1000, graceMs / 2);
        markerNanos = java.time.Duration.ofMillis(totalMs - markerMs).toNanos();
    }

    void start() {
        Thread guard = new Thread(() -> {
            if (await(completed, runNanos)) {
                return;
            }
            cancellation.set(true);
            if (!await(completed, markerNanos)) {
                terminate();
            }
        }, "faultora-mcp-deadline");
        guard.setDaemon(true);
        guard.start();
    }

    void complete() {
        if (state.compareAndSet(RUNNING, COMPLETED)) {
            completed.countDown();
        }
    }

    boolean unconfirmed() {
        return state.get() == UNCONFIRMED;
    }

    void join(Thread worker) throws InterruptedException {
        while (worker.isAlive()) {
            long remaining = markerNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                terminate();
                return;
            }
            TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
        }
    }

    private boolean await(CountDownLatch latch, long untilNanos) {
        while (true) {
            long remaining = untilNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                return latch.getCount() == 0;
            }
            try {
                return latch.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                // Neither an interrupted hook nor a callback may extend the absolute limit.
            }
        }
    }

    private void terminate() {
        try {
            if (state.compareAndSet(RUNNING, UNCONFIRMED)) {
                cancellation.set(true);
                Thread marker = new Thread(() -> {
                    try {
                        writeMarker();
                    } catch (IOException | RuntimeException unavailable) {
                        System.err.println("MCP termination is unconfirmed; marker could not be written");
                    } finally {
                        markerDone.countDown();
                    }
                }, "faultora-mcp-termination-marker");
                marker.setDaemon(true);
                marker.start();
            }
        } finally {
            if (unconfirmed()) {
                // Even marker-thread startup failure must not bypass the hard process limit.
                await(markerDone, haltNanos);
                Runtime.getRuntime().halt(EXIT_UNCONFIRMED);
            }
        }
    }

    private void writeMarker() throws IOException {
        McpConfiguration.requireDirectory(directory);
        String owner = McpJson.utf8(McpConfiguration.readFile(directory.resolve("run-owner"), 128));
        if (!owner.equals(runId)) {
            throw new IOException("Run ownership could not be verified");
        }
        var marker = McpJson.MAPPER.createObjectNode().put("runId", runId).put("status", "ERROR")
                .put("cleanupConfirmed", false).put("code", REASON);
        byte[] bytes = McpJson.MAPPER.writeValueAsString(marker).getBytes(StandardCharsets.UTF_8);
        try (var output = Files.newOutputStream(directory.resolve("termination.json"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
    }
}
