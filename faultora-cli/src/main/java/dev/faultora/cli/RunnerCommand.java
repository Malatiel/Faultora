package dev.faultora.cli;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.PolicyKeys;
import dev.faultora.runner.RunnerAgent;
import dev.faultora.runner.RunnerClient;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Session;
import dev.faultora.runtime.RunEnvironment;
import dev.faultora.spi.contract.SecretResolutionException;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code faultora runner} — serve a control plane from inside a private network.
 * <p>
 * The runner dials out and asks for work; nothing listens here. That is the
 * release gate stated as a shape rather than as a promise, and it is why this
 * command takes the address of a dispatcher and no port of its own.
 * <p>
 * What this adds to the pieces that already existed is the part that makes them
 * a process: configuration an operator writes down, a session that is
 * re-established when it dies, and a shutdown that lets the run in flight finish
 * rather than abandoning a journal nobody will ever collect.
 * <p>
 * It is the same composition root a local run uses. A runner is not a thin
 * agent — it compiles the scenario itself from the documents it was sent — and
 * that is exactly what keeps local and remote runs from drifting: the same code
 * over the same bytes.
 */
final class RunnerCommand implements Command {

    /** How long to wait before dialling again after the far side went away. */
    private static final long RECONNECT_DELAY_MS = 5_000;

    /**
     * How long to wait after being told there is nothing to do.
     * <p>
     * A poll is meant to be held open by the control plane and to come back
     * empty only after its own timeout, so this should never be reached. It is
     * here because a control plane that answers immediately would otherwise
     * have a runner asking it as fast as the network allows — a busy loop
     * against somebody else's service, produced by their bug and paid for by
     * both.
     */
    private static final long IDLE_PAUSE_MS = 1_000;

    private final PrintWriter out;
    private final PrintWriter err;

    RunnerCommand() {
        this(new PrintWriter(System.out, true), new PrintWriter(System.err, true));
    }

    RunnerCommand(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public int execute(List<String> args) {
        RunnerOptions options = RunnerOptions.parse(args);
        if (options.helpRequested()) {
            printUsage();
            return FaultoraCli.EXIT_PASS;
        }

        EnvironmentSecretResolver secrets = new EnvironmentSecretResolver();
        RunnerClient client = new RunnerClient(
                options.dispatcherUrl(),
                new TlsMaterial(options.keystore(), options.truststore(),
                        () -> passwordOf(secrets, options.tlsSecretId())),
                options.runnerId(), version(), RunEnvironment.PROTOCOLS_SPOKEN);

        DispatchedRun runs = new DispatchedRun(
                new DispatchVerifier(
                        options.limits(), new PolicyKeys(options.policyKeys())),
                options.workDirectory(),
                handleId -> resolveOrNothing(secrets, handleId),
                options.allowPrivate());

        RunnerAgent agent = new RunnerAgent(
                client, runs,
                RunPolicies.faultProviders(options.toxiproxyUrl()),
                new ExtensionPolicy(
                        Set.copyOf(options.allowedExtensions()), false, 0, Set.of(), Set.of()));

        announce(options);
        return serve(client, agent, options.once(), options.shutdownGraceMs());
    }

    /**
     * Ask for work until told to stop.
     * <p>
     * A session is re-established rather than held: a dispatcher restart is the
     * ordinary way one dies, and a runner that kept asking on a dead session
     * would look like a hang to somebody who cannot reach it.
     * <p>
     * Shutdown is where the exact wording matters. A signal stops this asking
     * for more work, and then <b>waits up to the grace period</b> for the run in
     * flight to finish and deliver what it found — a hook that only set a flag
     * would let the JVM exit mid-run, which is the "stopped and told nobody"
     * failure ADR-020 names. It is a bound and not a guarantee: a run longer
     * than the grace is cut off, and its journal is on disk. <b>A journal a
     * runner still holds when it stops is not re-delivered when it starts
     * again</b> — that is recorded as a gap rather than implied away here.
     */
    private int serve(RunnerClient client, RunnerAgent agent, boolean once, long graceMs) {
        AtomicBoolean serving = new AtomicBoolean(true);
        CountDownLatch finished = new CountDownLatch(1);
        Thread stopping = new Thread(() -> {
            serving.set(false);
            try {
                finished.await(graceMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "runner-shutdown");
        Runtime.getRuntime().addShutdownHook(stopping);
        try {
            String sessionId = null;
            while (serving.get()) {
                if (sessionId == null) {
                    sessionId = openSession(client);
                    if (sessionId == null) {
                        if (once || !pause(serving, RECONNECT_DELAY_MS)) {
                            return FaultoraCli.EXIT_RUNNER_FAILURE;
                        }
                        continue;
                    }
                }
                try {
                    Optional<DispatchedRun.Outcome> outcome = agent.takeOneDispatch(sessionId);
                    if (outcome.isPresent()) {
                        int served = report(outcome.get());
                        if (once) {
                            return served;
                        }
                    } else if (!pause(serving, IDLE_PAUSE_MS)) {
                        return FaultoraCli.EXIT_PASS;
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return FaultoraCli.EXIT_PASS;
                } catch (Exception unreachable) {
                    // The far side is gone, which is ordinary. The session it
                    // held is gone with it, so the next thing to do is register
                    // again rather than keep asking into nothing.
                    err.println("The dispatcher could not be reached: "
                            + unreachable.getMessage());
                    sessionId = null;
                    if (once || !pause(serving, RECONNECT_DELAY_MS)) {
                        return FaultoraCli.EXIT_RUNNER_FAILURE;
                    }
                }
            }
            return FaultoraCli.EXIT_PASS;
        } finally {
            finished.countDown();
            removeQuietly(stopping);
        }
    }

    /**
     * Register, or say why not.
     *
     * @return the session id, or null when there is no session to be had
     */
    private String openSession(RunnerClient client) {
        try {
            Session session = client.register();
            if (!session.isAccepted()) {
                // A named refusal, not a dropped connection: a deployment
                // mid-upgrade is the usual cause and the operator can act on it.
                err.println("Registration refused: " + session.refusal().describe());
                return null;
            }
            out.println("Registered with " + session.protocolVersion());
            return session.sessionId();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unreachable) {
            err.println("Could not register: " + unreachable.getMessage());
            return null;
        }
    }

    /**
     * Say what became of a dispatch, and what that is worth as an exit code.
     * <p>
     * A run whose assertions failed is a runner that did its job, so it is a
     * pass here — the failure belongs to the system under test and travels in
     * the result. A refusal and a run that broke part-way are the two cases
     * where the thing this process was asked to do did not happen, and
     * {@code --once} is often the smoke test of a fresh deployment.
     */
    private int report(DispatchedRun.Outcome outcome) {
        if (!outcome.didRun()) {
            out.println("Refused: " + outcome.refusal().describe());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
        if (!outcome.completed()) {
            out.println("Stopped part-way: " + outcome.failure());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
        out.println("Finished: " + outcome.result().status()
                + (outcome.leaseExpired() ? " (the lease ran out)" : "")
                + " — journal at " + outcome.journalPath());
        return FaultoraCli.EXIT_PASS;
    }

    /** Wait, unless we are being shut down meanwhile. */
    private boolean pause(AtomicBoolean serving, long millis) {
        try {
            Thread.sleep(millis);
            return serving.get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void removeQuietly(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The hook is running; there is nothing to remove.
        }
    }

    /**
     * The key material's password, from this runner's own environment.
     * <p>
     * Resolved on every read rather than held, because {@link TlsMaterial}
     * re-reads the files on every connection so that rotation is a file swap —
     * and a password cached here would make the one thing that must not need a
     * restart need one.
     * <p>
     * A handle that resolves to nothing throws from the resolver, naming the
     * environment variable it looked for, and that reaches the operator as the
     * reason the runner could not register.
     */
    private static char[] passwordOf(EnvironmentSecretResolver secrets, String handleId) {
        return secrets.resolve(handleId).secretValue();
    }

    /**
     * A handle a dispatch named, or nothing.
     * <p>
     * Nothing rather than an exception: a dispatch may name a credential this
     * deployment does not hold, and that is a step failing to authenticate —
     * diagnosable from the run's own journal — rather than the runner falling
     * over on somebody else's configuration.
     */
    private static SecretHandle resolveOrNothing(
            EnvironmentSecretResolver secrets, String handleId) {
        try {
            return secrets.resolve(handleId);
        } catch (SecretResolutionException notHere) {
            return null;
        }
    }

    private void announce(RunnerOptions options) {
        out.println("faultora runner " + options.runnerId());
        out.println("  dispatcher    " + options.dispatcherUrl());
        out.println("  work dir      " + options.workDirectory().toAbsolutePath());
        out.println("  protocols     " + String.join(", ",
                new java.util.TreeSet<>(RunEnvironment.PROTOCOLS_SPOKEN)));
        out.println("  policy keys   " + String.join(", ",
                new java.util.TreeSet<>(options.policyKeys().keySet())));
        out.println("  operations    " + String.join(", ",
                options.limits().allowedOperationClasses().stream()
                        .map(Enum::name).sorted().toList()));
        out.println("  keeps         " + describe(options.limits().maxEvidence()));
        out.println("  faults        " + (options.limits().allowedFaultTypes().isEmpty()
                ? "none" : String.join(", ",
                        new java.util.TreeSet<>(options.limits().allowedFaultTypes()))));
        out.println("  targets       " + (options.limits().allowedTargets().isEmpty()
                ? "any" : String.join(", ",
                        new java.util.TreeSet<>(options.limits().allowedTargets()))));
    }

    /** What a runner will hold on to, in the words an operator chose it with. */
    private static String describe(dev.faultora.model.security.EvidencePolicy evidence) {
        List<String> kept = new java.util.ArrayList<>();
        kept.add(evidence.captureBodies()
                ? "bodies up to " + evidence.maxBodyBytes() + " bytes" : "no bodies");
        kept.add(evidence.captureHeaders() ? "headers" : "no headers");
        kept.add("up to " + evidence.maxRows() + " rows");
        if (!evidence.redactPaths().isEmpty()) {
            kept.add("redacting " + String.join(", ", evidence.redactPaths()));
        }
        return String.join(", ", kept);
    }

    private static String version() {
        return Optional.ofNullable(
                RunnerCommand.class.getPackage().getImplementationVersion())
                .orElse("0.9.0-SNAPSHOT");
    }

    private void printUsage() {
        out.println("Usage: faultora runner --dispatcher <url> [options]");
        out.println();
        out.println("The runner dials out; nothing listens inside your network.");
        out.println();
        out.println("Required:");
        out.println("  --dispatcher <url>          Control plane to ask for work");
        out.println("  --keystore <file>           This runner's identity (PKCS#12)");
        out.println("  --truststore <file>         Control planes it will speak to");
        out.println("  --tls-secret-id <handle>    Where that material's password comes from");
        out.println("  --policy-key <id>=<file>    Verifying certificate per key id;"
                + " repeatable, and");
        out.println("                              a policy signed by anything else is refused");
        out.println();
        out.println("What this deployment permits (a dispatch narrows it, never widens it):");
        out.println("  --allow-fault <type>        Repeatable. Named NONE by default:"
                + " breaking");
        out.println("                              something is granted deliberately");
        out.println("  --allow-target <id>         Repeatable. Empty means ANY target");
        out.println("  --allow-environment <name>  Repeatable. Empty means ANY environment");
        out.println("  --allow-operation-class <c> Repeatable, and naming any REPLACES the");
        out.println("                              default READ_ONLY,MUTATING — so a runner");
        out.println("                              can be narrowed to reads alone. Name every");
        out.println("                              class you want; they are printed on start");
        out.println("  --allow-private             Permit private and loopback destinations");
        out.println("  --allow-extension <class>   Permit a non-built-in extension");
        out.println("  --no-capture-bodies         Keep no request or response bodies");
        out.println("  --no-capture-headers        Keep no headers");
        out.println("  --max-evidence-bytes <n>    Largest body kept (default 10485760)");
        out.println("  --max-evidence-rows <n>     Most database rows kept (default 1000)");
        out.println("  --redact <path>             Redact this path from what is kept;"
                + " repeatable");
        out.println("  --max-concurrency <n>       Default 10");
        out.println("  --max-duration <duration>   Default 300s");
        out.println("  --max-requests <n>          Default 1000");
        out.println("  --max-payload-bytes <n>     Default 1048576");
        out.println("  --toxiproxy-url <url>       Admin endpoint enabling network faults");
        out.println();
        out.println("Other:");
        out.println("  --work-dir <dir>            Where journals are written"
                + " (default faultora-runner-work)");
        out.println("  --runner-id <id>            Name at registration (default: hostname)");
        out.println("  --shutdown-grace <duration> How long a signal waits for the run in");
        out.println("                              flight to finish and report (default 30s)");
        out.println("  --once                      Take one dispatch and stop");
    }
}
