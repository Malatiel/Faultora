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
     *
     * @throws RuntimeException whatever the provider throws when injection fails
     */
    public ActiveFault start(
            FaultProvider provider,
            NodeId nodeId,
            String faultType,
            String targetScope,
            Map<String, Object> params,
            long durationMs
    ) {
        long hardExpiryMs = System.currentTimeMillis() + durationMs;
        FaultContext context = new FaultContext(targetScope, hardExpiryMs, Map.of());
        ActiveFault fault = provider.inject(faultType, params, context);

        FaultRecord record = new FaultRecord(provider, fault, context);
        records.put(fault.handle(), record);
        handlesByNode.put(nodeId, fault.handle());

        long delayMs = Math.max(0, fault.hardExpiryMs() - System.currentTimeMillis());
        record.watchdog = watchdogScheduler.schedule(
                () -> rollback(fault.handle(), REASON_HARD_EXPIRY),
                delayMs, TimeUnit.MILLISECONDS);
        return fault;
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
     */
    @Override
    public void close() {
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
