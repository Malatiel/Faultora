package dev.faultora.engine.fault;

import dev.faultora.model.identifier.NodeId;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the faults injected during a single run and guarantees rollback.
 * <p>
 * Every fault is rolled back exactly once, by whichever of these fires first:
 * an explicit fault-stop node, the hard-expiry watchdog, or the unconditional
 * end-of-run sweep in {@link #close()}. The watchdog runs on a daemon thread,
 * so a hung scenario cannot extend a fault beyond its hard expiry.
 */
public final class FaultSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FaultSession.class);

    /** Reason recorded when the hard-expiry watchdog rolls a fault back. */
    public static final String REASON_HARD_EXPIRY = "hard-expiry";
    /** Reason recorded when a fault-stop node rolls a fault back. */
    public static final String REASON_FAULT_STOP = "fault-stop";
    /** Reason recorded when the end-of-run sweep rolls a fault back. */
    public static final String REASON_RUN_END = "run-end";

    /** Receives exactly one callback per fault once its rollback has run. */
    public interface RollbackListener {
        void onRolledBack(ActiveFault fault, String rollbackStatus);
    }

    private static final class FaultRecord {
        final FaultProvider provider;
        final ActiveFault fault;
        final FaultContext context;
        final AtomicBoolean rolledBack = new AtomicBoolean(false);
        volatile ScheduledFuture<?> watchdog;

        FaultRecord(FaultProvider provider, ActiveFault fault, FaultContext context) {
            this.provider = provider;
            this.fault = fault;
            this.context = context;
        }
    }

    private final ScheduledExecutorService watchdogScheduler;
    private final ConcurrentMap<String, FaultRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeId, String> handlesByNode = new ConcurrentHashMap<>();
    private final RollbackListener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FaultSession(RollbackListener listener) {
        this.listener = listener;
        this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "faultora-fault-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Inject a fault and register its rollback obligation.
     * The watchdog is armed before this method returns.
     * <p>
     * A session that has closed — because the run was cancelled, or its
     * deadline passed while this fault was being injected — refuses to hand
     * back a fault it can no longer guarantee. Anything already injected at
     * that point is rolled back here rather than left behind: the end-of-run
     * sweep may have run before this fault was registered, and the watchdog
     * scheduler may already refuse work.
     *
     * @throws RuntimeException whatever the provider throws when injection fails
     * @throws IllegalStateException when the run ended around this injection
     */
    public ActiveFault start(
            FaultProvider provider,
            NodeId nodeId,
            String faultType,
            String targetScope,
            Map<String, Object> params,
            long durationMs
    ) {
        requireOpen();
        long hardExpiryMs = System.currentTimeMillis() + durationMs;
        FaultContext context = new FaultContext(targetScope, hardExpiryMs, Map.of());
        ActiveFault fault = provider.inject(faultType, params, context);

        FaultRecord record = new FaultRecord(provider, fault, context);
        records.put(fault.handle(), record);
        handlesByNode.put(nodeId, fault.handle());

        long delayMs = Math.max(0, fault.hardExpiryMs() - System.currentTimeMillis());
        try {
            record.watchdog = watchdogScheduler.schedule(
                    () -> rollback(fault.handle(), REASON_HARD_EXPIRY),
                    delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException sessionClosing) {
            abandon(fault);
        }
        // The record is registered and the watchdog armed. If the session
        // closed in between, its sweep either found this record — in which
        // case the rollback below is a no-op — or ran before the record
        // existed, in which case this is the only rollback there will be.
        if (closed.get()) {
            abandon(fault);
        }
        return fault;
    }

    /** Roll back a fault the session can no longer stand behind, and say so. */
    private void abandon(ActiveFault fault) {
        rollback(fault.handle(), REASON_RUN_END);
        throw new IllegalStateException(
                "The run ended while fault " + fault.handle()
                        + " was being injected; it has been rolled back");
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "The run has ended; no further fault may be injected");
        }
    }

    /** Handle of the fault started by the given plan node, or null. */
    public String handleForNode(NodeId faultStartNode) {
        return handlesByNode.get(faultStartNode);
    }

    /**
     * Roll back a fault exactly once.
     *
     * @return true if this call performed the rollback, false if the fault was
     *         unknown or already rolled back
     */
    public boolean rollback(String handle, String reason) {
        FaultRecord record = records.get(handle);
        if (record == null || !record.rolledBack.compareAndSet(false, true)) {
            return false;
        }
        ScheduledFuture<?> watchdog = record.watchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        String status = reason;
        try {
            record.provider.rollback(record.fault, record.context);
        } catch (Exception e) {
            LOG.warn("Fault rollback failed for {}: {}", handle, e.getMessage());
            status = reason + "-rollback-failed";
        }
        listener.onRolledBack(record.fault, status);
        return true;
    }

    /** Roll back every fault that is still active. */
    public void rollbackAll(String reason) {
        for (String handle : records.keySet()) {
            rollback(handle, reason);
        }
    }

    /** Number of faults not yet rolled back (for tests and diagnostics). */
    public int pendingCount() {
        return (int) records.values().stream()
                .filter(record -> !record.rolledBack.get())
                .count();
    }

    /**
     * Roll back all remaining faults and stop the watchdog. Waits briefly for
     * an in-flight watchdog rollback so its events land in the run journal.
     * <p>
     * The session is marked closed before the sweep, so a fault being injected
     * concurrently is either swept here or rolled back by {@link #start} — one
     * of the two always sees the other.
     */
    @Override
    public void close() {
        closed.set(true);
        rollbackAll(REASON_RUN_END);
        watchdogScheduler.shutdown();
        try {
            if (!watchdogScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                watchdogScheduler.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            watchdogScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
