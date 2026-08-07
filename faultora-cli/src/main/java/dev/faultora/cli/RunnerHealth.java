package dev.faultora.cli;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What a runner says about itself, for something that cannot ask it.
 * <p>
 * A runner has no port. That is the release gate — nothing listens inside the
 * private network — and it means a health endpoint is exactly the thing this
 * design exists to avoid. So the runner writes a file and a probe reads it with
 * {@code faultora health}, which is a command rather than a connection.
 * <p>
 * The file is rewritten on a timer rather than at each turn of the serve loop.
 * A run can take minutes, and a loop-driven file would go stale in the middle
 * of a perfectly healthy run — which a liveness probe would answer by killing
 * a runner that was working.
 * <p>
 * Written atomically, because a probe reading a half-written file would fail
 * for a reason that has nothing to do with the runner's health.
 */
final class RunnerHealth implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How often the file is rewritten. */
    private static final long TICK_MS = 5_000;

    /**
     * What a runner is doing, as a probe needs to read it.
     * <p>
     * The distinction that matters is between <b>live</b> and <b>ready</b>, and
     * for a process nothing connects to it is not the usual one. Live means the
     * loop is turning. Ready means a dispatcher has accepted a registration.
     * <b>A runner that cannot register must not fail liveness</b>: restarting
     * does not make an unreachable control plane reachable, and a control-plane
     * hiccup would otherwise restart every runner in a fleet at once.
     *
     * @param state           what the runner is doing
     * @param runnerId        which runner this is
     * @param updatedAtEpochMs when this file was last written — the freshness a
     *                        liveness probe reads
     * @param registered      whether a dispatcher has a session with this runner
     * @param currentRunId    the run in flight, or null
     * @param runsServed      how many dispatches this process has seen through
     */
    record Status(
            String state, String runnerId, long updatedAtEpochMs,
            boolean registered, String currentRunId, long runsServed) {

        /** Before anything has been attempted, so a probe never reads an empty file. */
        static final String STARTING = "STARTING";
        /** Dialling out, or dialling again after losing a session. */
        static final String REGISTERING = "REGISTERING";
        /** Registered and asking for work. */
        static final String WAITING = "WAITING";
        /** Executing a dispatch. */
        static final String RUNNING = "RUNNING";
        /** Told to stop; finishing what is in flight. */
        static final String STOPPING = "STOPPING";

        @JsonIgnore
        boolean isFresh(long nowEpochMs, long maxAgeMs) {
            return nowEpochMs - updatedAtEpochMs <= maxAgeMs;
        }
    }

    private final Path file;
    private final String runnerId;
    private volatile String state = Status.STARTING;
    private volatile boolean registered;
    private volatile String currentRunId;
    private volatile long runsServed;
    private final Thread ticker;

    private RunnerHealth(Path file, String runnerId) {
        this.file = file;
        this.runnerId = runnerId;
        this.ticker = new Thread(this::tickUntilStopped, "runner-health");
        this.ticker.setDaemon(true);
    }

    /**
     * Begin reporting, before anything has been attempted.
     * <p>
     * The first write happens here rather than after the first registration: a
     * startup probe reading a file that does not exist yet would kill the
     * container while it was still dialling.
     */
    static RunnerHealth reporting(Path file, String runnerId) throws IOException {
        RunnerHealth health = new RunnerHealth(file, runnerId);
        Files.createDirectories(file.toAbsolutePath().getParent());
        health.write();
        health.ticker.start();
        return health;
    }

    void registering() {
        state = Status.REGISTERING;
        registered = false;
        writeQuietly();
    }

    void waiting() {
        state = Status.WAITING;
        registered = true;
        currentRunId = null;
        writeQuietly();
    }

    void running(String runId) {
        state = Status.RUNNING;
        currentRunId = runId;
        writeQuietly();
    }

    void served() {
        runsServed++;
        currentRunId = null;
    }

    void stopping() {
        state = Status.STOPPING;
        writeQuietly();
    }

    private void tickUntilStopped() {
        while (!Thread.currentThread().isInterrupted()) {
            writeQuietly();
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeQuietly() {
        try {
            write();
        } catch (IOException cannotWrite) {
            // The probe will see a stale file and act on it, which is the
            // correct outcome: a runner that cannot write its working
            // directory is a runner that cannot journal a run either.
        }
    }

    private synchronized void write() throws IOException {
        Status status = new Status(
                state, runnerId, System.currentTimeMillis(),
                registered, currentRunId, runsServed);
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.writeString(partial, MAPPER.writeValueAsString(status));
        Files.move(partial, file,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Read a status file, or throw saying why it could not be read. */
    static Status read(Path file) throws IOException {
        return MAPPER.readValue(Files.readString(file), Status.class);
    }

    @Override
    public void close() {
        ticker.interrupt();
    }
}
