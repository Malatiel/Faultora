package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.spi.contract.FaultProvider;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The shortest a renewal interval is honoured at.
     * <p>
     * The interval itself comes from the lease. This only keeps a dispatcher
     * that asks to be asked every millisecond from turning the heartbeat into
     * a spin — a floor on the runner's own resources, not a second opinion
     * about the lease.
     */
    private static final long SHORTEST_HEARTBEAT_MS = 50;

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

        JournalTail[] tail = new JournalTail[1];
        Thread[] keepingAlive = new Thread[1];
        DispatchedRun.Outcome outcome = runs.execute(
                dispatch, faultProviders, extensions,
                (lease, journalPath) -> {
                    tail[0] = new JournalTail(journalPath);
                    keepingAlive[0] = new Thread(
                            () -> keepAlive(dispatch.runId(), lease, tail[0]),
                            "runner-heartbeat-" + dispatch.runId());
                    keepingAlive[0].setDaemon(true);
                    keepingAlive[0].start();
                });
        if (keepingAlive[0] != null) {
            keepingAlive[0].interrupt();
            keepingAlive[0].join(2_000);
        }

        deliverWhatIsLeft(dispatch.runId(), outcome, tail[0]);
        return Optional.of(outcome);
    }

    /**
     * Renew the permission and send what has been written, until the run ends.
     * <p>
     * Failures here are swallowed on purpose. The far side being unreachable is
     * the case this whole arrangement exists for, and the answer to it is to
     * stop renewing — not to raise something. The lease does the rest.
     */
    private void keepAlive(String runId, LeaseWatch lease, JournalTail tail) {
        while (!Thread.currentThread().isInterrupted() && !lease.hasExpired()) {
            try {
                Optional<Lease> renewed = client.heartbeat(runId);
                if (renewed.isPresent()) {
                    lease.renew(renewed.get(), System.currentTimeMillis());
                }
                sendWhateverIsFinished(runId, tail);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unreachable) {
                // Nothing is renewed, which is what makes the run stop.
            }
            try {
                Thread.sleep(Math.max(SHORTEST_HEARTBEAT_MS, lease.renewEveryMs()));
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Send the part of the journal that is finished being written.
     * <p>
     * Finished, not merely present: the tail hands over whole lines only, so a
     * half-written event stays here until it is an event. Delivering it would
     * move the far side's position past a fragment that no re-send can repair.
     */
    private void sendWhateverIsFinished(String runId, JournalTail tail)
            throws IOException, InterruptedException {
        JournalTail.Batch batch = tail.next();
        if (!batch.isEmpty()) {
            tail.delivered(batch, client.sendProgress(
                    runId, batch.fromPosition(), batch.lines()));
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
            String runId, DispatchedRun.Outcome outcome, JournalTail tail) {
        try {
            if (tail != null) {
                sendWhateverIsFinished(runId, tail);
            } else if (outcome.journalPath() != null) {
                sendWhateverIsFinished(runId, new JournalTail(outcome.journalPath()));
            }
            client.sendOutcome(runId, describe(outcome));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (Exception stillUnreachable) {
            // The journal is on disk and the position is known; the next
            // attempt resumes from where this one stopped.
        }
    }

    /**
     * What became of the run, as the far side is told it.
     * <p>
     * A run that broke says why. It used to say only that it had, which left
     * the one case with no diagnosis at all the only case that needed one —
     * a refusal already names its reason, and a run that finished has a whole
     * journal behind it.
     */
    // Package-private so the shape of what the far side is told can be
    // asserted directly. A run that broke is the case with no other
    // diagnosis, and it is the one that was empty.
    static String describe(DispatchedRun.Outcome outcome) {
        ObjectNode described = MAPPER.createObjectNode();
        if (outcome.refusal() != null) {
            described.put("refused", outcome.refusal().reason().toString());
            described.put("why", outcome.refusal().describe());
        } else if (outcome.failure() != null) {
            described.put("broke", true);
            described.put("why", outcome.failure());
        } else {
            described.put("status", outcome.result().status().toString());
            described.put("leaseExpired", outcome.leaseExpired());
        }
        return described.toString();
    }
}
