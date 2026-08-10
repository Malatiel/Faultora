package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A runner running in its own process, and a dispatcher to give it work.
 * <p>
 * Two things can only be observed from outside the JVM the runner is in: what a
 * signal does to it, and whether it has opened a listening socket. Both tests
 * need the same setup — key material, a signing key, a dispatcher, and a
 * command line — so it lives here rather than twice.
 * <p>
 * The class path is this test's own, so the process runs the code that was just
 * compiled rather than something installed.
 */
final class RunnerProcess implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The handle the surefire environment binds the key material's password to. */
    private static final String TLS_SECRET_ID = "runner-tls";

    private final QualificationDispatcher dispatcher;
    private final Certificates.Identity signingKey;
    private final Process process;

    private RunnerProcess(
            QualificationDispatcher dispatcher,
            Certificates.Identity signingKey,
            Process process) {
        this.dispatcher = dispatcher;
        this.signingKey = signingKey;
        this.process = process;
    }

    /**
     * Issue what a deployment issues, start a dispatcher, and start a runner.
     *
     * @param extraArguments appended to the command line, for what a test is
     *                       actually about
     */
    static RunnerProcess start(Path directory, String... extraArguments) throws Exception {
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity control = Certificates.issue(directory, "control", 1);
        Certificates.Identity signing = Certificates.issue(directory, "policy-signing", 1);

        QualificationDispatcher dispatcher = new QualificationDispatcher(new TlsMaterial(
                control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray()).sslContext());

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "dev.faultora.cli.FaultoraCli", "runner",
                "--dispatcher", dispatcher.address(),
                "--keystore", runner.keystore().toString(),
                "--truststore", Certificates.trusting(directory, "runner", control).toString(),
                "--tls-secret-id", TLS_SECRET_ID,
                "--policy-key", "control-2026=" + signing.certificate(),
                "--work-dir", directory.resolve("work").toString(),
                "--runner-id", "runner-under-test",
                "--allow-private"));
        command.addAll(List.of(extraArguments));

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("FAULTORA_SECRET_RUNNER_TLS", Certificates.PASSWORD);
        return new RunnerProcess(dispatcher, signing, builder.start());
    }

    QualificationDispatcher dispatcher() {
        return dispatcher;
    }

    Process process() {
        return process;
    }

    /** Offer a scenario to whatever asks next, under a policy this runner trusts. */
    void offer(String runId, String scenario) throws Exception {
        String policy = MAPPER.writeValueAsString(EffectivePolicy.of(new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY),
                100, 2, 60_000, 1024, Set.of(), Set.of())));
        dispatcher.offer(new Dispatch(
                runId, System.currentTimeMillis(), runId + "-nonce", scenario, List.of(),
                Map.of(), Map.of("", "http://localhost:1"), Dispatch.Credentials.none(),
                3L,
                new SignedPolicy(policy, "control-2026",
                        Certificates.sign(signingKey, "RSA", policy)),
                new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(List.of())));
    }

    /** Wait until the runner has dialled out and been accepted. */
    void awaitRegistered() throws InterruptedException {
        await(() -> !dispatcher.registrations().isEmpty(), "never registered");
    }

    /** Wait until a run is under way, so a signal lands in the middle of it. */
    void awaitRunUnderWay(String runId) throws InterruptedException {
        await(() -> !dispatcher.journalOf(runId).isEmpty(), "never started " + runId);
    }

    private void await(java.util.function.BooleanSupplier until, String otherwise)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (until.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the runner " + otherwise);
    }

    @Override
    public void close() {
        process.destroyForcibly();
        dispatcher.close();
    }
}
