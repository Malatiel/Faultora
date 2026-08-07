package dev.faultora.cli;

import dev.faultora.model.catalog.SafetyClassification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a deployment says it permits.
 * <p>
 * These are not argument-parsing tests with a security flavour; the values
 * here <em>are</em> the runner's floor, and a dispatched policy can only
 * narrow them. A default that came out wrong would be a deployment permitting
 * something nobody asked it to permit, and it would look like nothing at all.
 */
class RunnerOptionsTest {

    private static final List<String> REQUIRED = List.of(
            "--dispatcher", "https://control.internal:8443",
            "--keystore", "/etc/faultora/runner.p12",
            "--truststore", "/etc/faultora/trusted.p12",
            "--tls-secret-id", "runner-tls",
            "--policy-key", "control-2026=/etc/faultora/policy.crt");

    private static RunnerOptions parse(String... extra) {
        List<String> args = new java.util.ArrayList<>(REQUIRED);
        args.addAll(List.of(extra));
        return RunnerOptions.parse(args);
    }

    @Test
    void aRunnerBreaksNothingUntilSomebodySaysItMay() {
        // The asymmetry that matters. Empty means "any" for targets and
        // environments, and "none" for faults — because listing no targets is
        // a deployment that has not thought about target ids, and listing no
        // faults is a deployment that has not granted the ability to break
        // anything. A runner that read the second the way it reads the first
        // would inject whatever it was sent.
        assertThat(parse().limits().allowedFaultTypes()).isEmpty();
        assertThat(parse("--allow-fault", "http-latency").limits().allowedFaultTypes())
                .containsExactly("http-latency");
    }

    @Test
    void destructiveOperationsAreNotPermittedByDefault() {
        assertThat(parse().limits().allowedOperationClasses())
                .containsExactlyInAnyOrder(
                        SafetyClassification.READ_ONLY, SafetyClassification.MUTATING);

        assertThat(parse("--allow-operation-class", "DESTRUCTIVE")
                .limits().allowedOperationClasses())
                .as("naming one replaces the default rather than adding to it")
                .containsExactly(SafetyClassification.DESTRUCTIVE);
    }

    @Test
    void theBoundsAreTheOnesALocalRunHas() {
        // A scenario that fits on an engineer's machine has to fit on a
        // runner, or a scenario is refused remotely for a reason its author
        // cannot see in it.
        assertThat(parse().limits().maxConcurrency()).isEqualTo(10);
        assertThat(parse().limits().maxDurationMs()).isEqualTo(300_000);
        assertThat(parse().limits().maxRequests()).isEqualTo(1000);
        assertThat(parse().limits().maxPayloadBytes()).isEqualTo(1_048_576);
    }

    @Test
    void aDurationIsWrittenTheWayAScenarioWritesOne() {
        assertThat(parse("--max-duration", "90s").limits().maxDurationMs())
                .isEqualTo(90_000);
        assertThat(parse("--max-duration", "2m").limits().maxDurationMs())
                .isEqualTo(120_000);

        assertThatThrownBy(() -> parse("--max-duration", "a while"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--max-duration");
    }

    @Test
    void aRunnerWithNoVerifyingKeyWillNotStart() {
        // The refusal that matters most, because the alternative starts fine.
        // Mutual TLS says who is speaking; only a signature says the policy is
        // the one that was issued, so a runner without a key to check it
        // against would take its permissions from whoever reached it.
        assertThatThrownBy(() -> RunnerOptions.parse(List.of(
                "--dispatcher", "https://control.internal:8443",
                "--keystore", "/etc/faultora/runner.p12",
                "--truststore", "/etc/faultora/trusted.p12",
                "--tls-secret-id", "runner-tls")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--policy-key");
    }

    @Test
    void keysAreNamedSoARolloverCanOverlap() {
        RunnerOptions options = parse(
                "--policy-key", "control-2027=/etc/faultora/next.crt");

        assertThat(options.policyKeys()).containsOnlyKeys("control-2026", "control-2027");
    }

    @Test
    void aKeyWithoutAnIdIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> parse("--policy-key", "/etc/faultora/policy.crt"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("<id>=<file>");
    }

    @Test
    void everyRequiredOptionIsNamedWhenItIsMissing() {
        // A runner is usually configured by somebody who cannot watch it start.
        assertThatThrownBy(() -> RunnerOptions.parse(List.of()))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--dispatcher");
        assertThatThrownBy(() -> RunnerOptions.parse(List.of(
                "--dispatcher", "https://control.internal:8443")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--keystore");
    }

    @Test
    void anUnknownOptionIsNotSilentlyIgnored() {
        // An ignored --allow-fault is a deployment that believes it granted
        // something and did not, or worse, believes it restricted something.
        assertThatThrownBy(() -> parse("--allow-everything"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--allow-everything");
    }

    @Test
    void aDeploymentCanRefuseToHoldWhatItIsSent() {
        // The posture this exists for: bodies may be read by a run inside the
        // network and must not be kept where they could leave it. A dispatch
        // asking for bodies is narrowed to none rather than refused, so the run
        // still happens and the assertions that needed a body say they could
        // not be evaluated.
        assertThat(parse().limits().maxEvidence().captureBodies()).isTrue();
        assertThat(parse("--no-capture-bodies").limits().maxEvidence().captureBodies())
                .isFalse();
        assertThat(parse("--no-capture-headers").limits().maxEvidence().captureHeaders())
                .isFalse();
    }

    @Test
    void howMuchIsKeptIsANumberAnOperatorChooses() {
        assertThat(parse().limits().maxEvidence().maxRows()).isEqualTo(1000);
        assertThat(parse("--max-evidence-rows", "10").limits().maxEvidence().maxRows())
                .isEqualTo(10);
        assertThat(parse("--max-evidence-bytes", "4096")
                .limits().maxEvidence().maxBodyBytes()).isEqualTo(4096);
    }

    @Test
    void aPathADeploymentRedactsIsRedactedWhateverADispatchAsks() {
        assertThat(parse("--redact", "body.pan", "--redact", "body.email")
                .limits().maxEvidence().redactPaths())
                .containsExactly("body.pan", "body.email");
    }

    @Test
    void helpNeedsNoConfigurationToBeAsking() {
        assertThat(RunnerOptions.parse(List.of("--help")).helpRequested()).isTrue();
    }
}
