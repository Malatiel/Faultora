package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner as a process an operator starts.
 * <p>
 * Everything crosses a real socket under mutual TLS, and the policy is really
 * signed — the whole point of this command is that a runner takes its
 * permissions from a signature rather than from whoever reached it, and a
 * fixture signature would let a verifier that accepts anything pass.
 * <p>
 * Nothing listens here. The command is given an address to dial and has no
 * port of its own, which is the release gate expressed as a shape.
 */
class RunnerCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The handle the surefire environment binds the key material's password to. */
    private static final String TLS_SECRET_ID = "runner-tls";

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: served-by-a-runner
            execute:
              - id: pause
                type: wait
                timeout: 20ms
            """;

    @TempDir
    Path directory;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    /** A runner, a dispatcher, and a signing key — as a deployment issues them. */
    private record Deployment(
            QualificationDispatcher dispatcher, List<String> args,
            Certificates.Identity signingKey) {
    }

    private Deployment deploy(String... trustedKeyFiles) throws Exception {
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity control = Certificates.issue(directory, "control", 1);
        Certificates.Identity signing = Certificates.issue(directory, "policy-signing", 1);

        QualificationDispatcher dispatcher = new QualificationDispatcher(
                new dev.faultora.runner.TlsMaterial(
                        control.keystore(),
                        Certificates.trusting(directory, "control", runner),
                        () -> Certificates.PASSWORD.toCharArray()).sslContext());

        List<String> args = new ArrayList<>(List.of(
                "--dispatcher", dispatcher.address(),
                "--keystore", runner.keystore().toString(),
                "--truststore", Certificates.trusting(directory, "runner", control).toString(),
                "--tls-secret-id", TLS_SECRET_ID,
                "--work-dir", directory.resolve("work").toString(),
                "--runner-id", "runner-under-test",
                "--allow-private",
                "--once"));
        for (String keyFile : trustedKeyFiles.length == 0
                ? new String[]{signing.certificate().toString()} : trustedKeyFiles) {
            args.add("--policy-key");
            args.add("control-2026=" + keyFile);
        }
        return new Deployment(dispatcher, args, signing);
    }

    /** The policy a dispatch carries, signed by the key named in it. */
    private static SignedPolicy policySignedBy(Certificates.Identity key) throws Exception {
        String policy = MAPPER.writeValueAsString(EffectivePolicy.of(new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY),
                100, 2, 60_000, 1024, Set.of(), Set.of())));
        return new SignedPolicy(
                policy, "control-2026", Certificates.sign(key, "RSA", policy));
    }

    private static Dispatch dispatch(String runId, SignedPolicy policy) {
        return new Dispatch(
                runId, System.currentTimeMillis(), runId + "-nonce", SCENARIO, List.of(),
                Map.of(), Map.of("", "http://localhost:1"), Dispatch.Credentials.none(),
                17L, policy, new Lease(System.currentTimeMillis(), 60_000, 10_000),
                ContentDigest.sha256Uri(SCENARIO), Dispatch.digestOfDocuments(List.of()));
    }

    private int run(List<String> args) {
        return new RunnerCommand(new PrintWriter(out, true), new PrintWriter(err, true))
                .execute(args);
    }

    @Test
    void theRunnerDialsOutTakesAJobAndRunsIt() throws Exception {
        Deployment deployment = deploy();
        try (QualificationDispatcher dispatcher = deployment.dispatcher()) {
            dispatcher.offer(dispatch("run-served-1",
                    policySignedBy(deployment.signingKey())));

            int exit = run(deployment.args());

            assertThat(exit).as(err::toString).isEqualTo(FaultoraCli.EXIT_PASS);
            assertThat(out.toString()).contains("Finished: PASSED");
            assertThat(dispatcher.registrations()).singleElement().satisfies(registration -> {
                assertThat(registration.runnerId()).isEqualTo("runner-under-test");
                assertThat(registration.capabilities())
                        .as("what it advertises is what the composition root opens")
                        .containsExactlyInAnyOrderElementsOf(
                                dev.faultora.runtime.RunEnvironment.PROTOCOLS_SPOKEN);
            });
            assertThat(dispatcher.journalOf("run-served-1"))
                    .as("what the run learned reached the far side")
                    .isNotEmpty();
            assertThat(dispatcher.outcomeOf("run-served-1")).contains("PASSED");
        }
    }

    @Test
    void aPolicySignedByAKeyThisRunnerDoesNotHoldIsRefused() throws Exception {
        // The half that shows the half above proves something. Same dispatch,
        // same connection, same run id shape — only the signature differs, and
        // the runner declines rather than running under permissions nobody it
        // trusts issued.
        Deployment deployment = deploy();
        Certificates.Identity stranger = Certificates.issue(directory, "stranger", 1);

        try (QualificationDispatcher dispatcher = deployment.dispatcher()) {
            dispatcher.offer(dispatch("run-served-2", policySignedBy(stranger)));

            int exit = run(deployment.args());

            assertThat(exit).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
            assertThat(out.toString()).contains("Refused:");
            assertThat(dispatcher.journalOf("run-served-2"))
                    .as("nothing ran, so there is nothing to report")
                    .isEmpty();
        }
    }

    @Test
    void aDispatcherThatIsNotThereIsSaidSoRatherThanHungOn() throws Exception {
        // A runner inside a private network that quietly retried forever is
        // indistinguishable from one that is broken, to the only person who
        // can fix it and cannot reach it.
        Deployment deployment = deploy();
        String address;
        try (QualificationDispatcher dispatcher = deployment.dispatcher()) {
            address = dispatcher.address();
        }
        List<String> args = new ArrayList<>(deployment.args());
        args.set(args.indexOf("--dispatcher") + 1, address);

        int exit = run(args);

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(err.toString()).contains("Could not register");
    }

    @Test
    void anIdleDispatcherIsWaitedOnRatherThanTreatedAsAFailure() throws Exception {
        // Nothing to do is the ordinary answer, and a runner that took it for
        // an error would give up on a control plane that is merely quiet. The
        // runner is started against an empty dispatcher and the work arrives a
        // second later, so every poll before it came back with nothing.
        Deployment deployment = deploy();
        try (QualificationDispatcher dispatcher = deployment.dispatcher()) {
            Thread serving = new Thread(() -> run(deployment.args()), "runner");
            serving.setDaemon(true);
            serving.start();
            Thread.sleep(1_500);

            dispatcher.offer(dispatch("run-served-3",
                    policySignedBy(deployment.signingKey())));
            serving.join(60_000);

            assertThat(out.toString()).contains("Finished: PASSED");
            assertThat(err.toString())
                    .as("an empty answer is not something to complain about")
                    .isEmpty();
        }
    }

    @Test
    void whatADeploymentPermitsIsPrintedWhereSomebodyWillSeeIt() throws Exception {
        // A runner's limits are its most consequential configuration and the
        // easiest to get wrong silently. It says them out loud on startup —
        // in particular that it will break nothing until told it may.
        Deployment deployment = deploy();
        try (QualificationDispatcher dispatcher = deployment.dispatcher()) {
            dispatcher.offer(dispatch("run-served-4",
                    policySignedBy(deployment.signingKey())));

            run(deployment.args());

            assertThat(out.toString())
                    .contains("faults        none")
                    .contains("targets       any")
                    .contains("policy keys   control-2026");
        }
    }
}
