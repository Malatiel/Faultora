package dev.faultora.runner;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.spi.contract.FaultProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runner as a thing that runs: ask for work, do it, keep saying so.
 * <p>
 * The three pieces already existed separately — a client that speaks, a
 * verifier that decides, a run that executes — and this is what makes them a
 * runner. What it adds is the part that only matters while a run is in flight:
 * <ul>
 *   <li><b>The lease is renewed by asking.</b> A heartbeat goes out every so
 *       often and comes back as a new permission. Nothing here extends
 *       anything on its own.</li>
 *   <li><b>The journal is sent as it grows</b>, from the position the far side
 *       last acknowledged.</li>
 * </ul>
 * Both stop working the moment the far side is unreachable, and that is the
 * design rather than a gap in it. Nothing renews the lease, so it runs out, and
 * the run stops on the runner's own clock — which is the release gate:
 * <em>disconnection cannot extend a run or an active fault beyond policy</em>.
 * The findings are not lost, because the journal is a file, and they are
 * delivered when somebody can be reached again.
 */
public final class RunnerAgent {

    private final RunnerClient client;
    private final DispatchedRun runs;
    private final Map<String, FaultProvider> faultProviders;
    private final ExtensionPolicy extensions;

    public RunnerAgent(
            RunnerClient client,
            DispatchedRun runs,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions
    ) {
        this.client = client;
        this.runs = runs;
        this.faultProviders = faultProviders;
        this.extensions = extensions;
    }

    /**
     * Take one dispatch and see it through.
     *
     * @return what became of it, or empty when there was nothing to do
     */
    public Optional<DispatchedRun.Outcome> takeOneDispatch(String sessionId)
            throws IOException, InterruptedException {
        Optional<Dispatch> work = client.pollForWork(sessionId);
        if (work.isEmpty()) {
            return Optional.empty();
        }
        Dispatch dispatch = work.get();

        AtomicLong delivered = new AtomicLong();
        Thread[] keepingAlive = new Thread[1];
        DispatchedRun.Outcome outcome = runs.execute(
                dispatch, faultProviders, extensions,
                (lease, journalPath) -> {
                    keepingAlive[0] = new Thread(
                            () -> keepAlive(dispatch.runId(), lease, journalPath, delivered),
                            "runner-heartbeat-" + dispatch.runId());
                    keepingAlive[0].setDaemon(true);
                    keepingAlive[0].start();
                });
        if (keepingAlive[0] != null) {
            keepingAlive[0].interrupt();
            keepingAlive[0].join(2_000);
        }

        deliverWhatIsLeft(dispatch.runId(), outcome, delivered);
        return Optional.of(outcome);
    }

    /**
     * Renew the permission and send what has been written, until the run ends.
     * <p>
     * Failures here are swallowed on purpose. The far side being unreachable is
     * the case this whole arrangement exists for, and the answer to it is to
     * stop renewing — not to raise something. The lease does the rest.
     */
    private void keepAlive(
            String runId, LeaseWatch lease, Path journalPath, AtomicLong delivered) {
        while (!Thread.currentThread().isInterrupted() && !lease.hasExpired()) {
            try {
                Optional<Lease> renewed = client.heartbeat(runId);
                if (renewed.isPresent()) {
                    lease.renew(renewed.get(), System.currentTimeMillis());
                }
                sendWhateverIsNew(runId, journalPath, delivered);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unreachable) {
                // Nothing is renewed, which is what makes the run stop.
            }
            try {
                Thread.sleep(heartbeatInterval(lease));
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Often enough that one lost heartbeat is survivable. */
    private static long heartbeatInterval(LeaseWatch lease) {
        return Math.max(50, Math.min(1_000, lease.remainingMs() / 3));
    }

    private void sendWhateverIsNew(String runId, Path journalPath, AtomicLong delivered)
            throws IOException, InterruptedException {
        if (!Files.exists(journalPath)) {
            return;
        }
        List<String> lines = Files.readAllLines(journalPath);
        long from = delivered.get();
        if (lines.size() > from) {
            delivered.set(client.sendProgress(
                    runId, from, lines.subList((int) from, lines.size())));
        }
    }

    /**
     * Deliver the tail of the journal and say what happened.
     * <p>
     * Attempted once the run is over, whether it finished, was stopped by its
     * lease, or broke part-way. A run that was bounded and whose findings never
     * arrived has failed the gate from the other side.
     */
    private void deliverWhatIsLeft(
            String runId, DispatchedRun.Outcome outcome, AtomicLong delivered) {
        try {
            if (outcome.journalPath() != null) {
                sendWhateverIsNew(runId, outcome.journalPath(), delivered);
            }
            client.sendOutcome(runId, describe(outcome));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (Exception stillUnreachable) {
            // The journal is on disk and the position is known; the next
            // attempt resumes from where this one stopped.
        }
    }

    private static String describe(DispatchedRun.Outcome outcome) {
        if (outcome.refusal() != null) {
            return "{\"refused\":\"" + outcome.refusal().reason() + "\"}";
        }
        if (outcome.failure() != null) {
            return "{\"broke\":true}";
        }
        return "{\"status\":\"" + outcome.result().status()
                + "\",\"leaseExpired\":" + outcome.leaseExpired() + "}";
    }
}
