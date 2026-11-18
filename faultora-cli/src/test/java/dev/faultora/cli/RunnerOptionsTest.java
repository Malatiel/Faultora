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
    void durableDeliveryRequiresExplicitOptInAndAnAuthenticatedDispatcher() {
        assertThat(parse().durableDelivery()).isFalse();
        assertThat(parse("--durable-delivery").durableDelivery()).isTrue();
        assertThatThrownBy(() -> parse("--durable-delivery", "--dispatcher", "http://control.example.invalid"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--dispatcher requires an HTTPS origin");
        assertThatThrownBy(() -> parse("--durable-delivery", "--dispatcher", "https://user@control.example.invalid"))
                .isInstanceOf(CliException.class)
                .hasMessageNotContaining("user@");
    }

    @Test
    void defaultProtocolRequiresAnHttpsOriginAndWithholdsRefusedAddresses() {
        assertThat(parse("--dispatcher", "https://control.example.invalid:8443/").dispatcherUrl().getPort())
                .isEqualTo(8443);
        for (String invalid : List.of("http://synthetic-marker.example.invalid",
                "https://synthetic-marker@control.example.invalid",
                "https://control.example.invalid/synthetic-marker",
                "https://control.example.invalid?synthetic-marker",
                "https://control.example.invalid/#synthetic-marker",
                "https://synthetic-marker example.invalid")) {
            assertThatThrownBy(() -> parse("--dispatcher", invalid))
                    .isInstanceOfSatisfying(CliException.class,
                            error -> assertThat(error.exitCode()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG))
                    .hasMessageContaining("--dispatcher requires an HTTPS origin")
                    .hasMessageNotContaining("synthetic-marker");
        }
    }

    @Test
    void isolatedAssertionUsesOneDigestAndCannotEnableClasspathExtensions() {
        String digest = "sha256:" + "a".repeat(64);
        assertThat(parse().isolatedAssertion()).isNull();
        var approval = parse("--isolated-assertion", "custom-check", digest, "fixture.jar").isolatedAssertion();
        assertThat(approval.type()).isEqualTo("custom-check");
        assertThat(approval.digest()).isEqualTo(digest);
        assertThat(approval.resourceLimits()).isNull();
        assertThatThrownBy(() -> parse("--isolated-assertion", "custom-check", digest, "fixture.jar",
                "--allow-extension", "example.Provider"))
                .isInstanceOf(CliException.class).hasMessageContaining("cannot be combined");
        assertThatThrownBy(() -> parse("--isolated-assertion", "custom-check", digest, "fixture.jar",
                "--isolated-assertion", "other-check", digest, "other.jar"))
                .isInstanceOf(CliException.class).hasMessageContaining("may be used once");
        assertThatThrownBy(() -> parse("--isolated-assertion", "custom-check", digest, "fixture.jar",
                "--durable-delivery", "--retire-acknowledged", "synthetic-run",
                "--acknowledged-before", "2026-01-01T00:00:00Z"))
                .isInstanceOf(CliException.class).hasMessageContaining("only available when serving dispatches");
    }

    @Test
    void requiringTheExtensionSandboxNeedsLinuxAndAnApprovedAssertion() {
        assertThatThrownBy(() -> parse("--require-extension-sandbox"))
                .isInstanceOf(CliException.class).hasMessageContaining("requires Linux and --isolated-assertion");
        String[] args = {"--require-extension-sandbox", "--isolated-assertion", "custom-check",
                "sha256:" + "a".repeat(64), "fixture.jar"};
        if (System.getProperty("os.name").equals("Linux")) {
            var assertion = parse(args).isolatedAssertion();
            assertThat(assertion.requireOsSandbox()).isTrue();
            assertThat(assertion.resourceLimits()).isNull();
        } else {
            assertThatThrownBy(() -> parse(args)).isInstanceOf(CliException.class)
                    .hasMessageContaining("requires Linux and --isolated-assertion");
        }
    }

    @Test
    void extensionResourceLimitsRequireBothBoundsAndTheSandbox() {
        assertThatThrownBy(() -> parse("--extension-memory-mib", "384"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("must be supplied together");
        assertThatThrownBy(() -> parse("--extension-cpu-percent", "50"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("must be supplied together");
        assertThatThrownBy(() -> parse("--isolated-assertion", "custom-check",
                "sha256:" + "a".repeat(64), "fixture.jar",
                "--extension-memory-mib", "384", "--extension-cpu-percent", "50"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("requires --require-extension-sandbox");
    }

    @Test
    void extensionBoundsRejectMalformedOutOfRangeAndRepeatedValues() {
        for (String invalid : List.of("0", "-1", "2147483648", "384MiB")) {
            assertThatThrownBy(() -> parse("--extension-memory-mib", invalid))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--extension-memory-mib expects an integer from 1");
        }
        for (String invalid : List.of("0", "101", "50.5", "50%")) {
            assertThatThrownBy(() -> parse("--extension-cpu-percent", invalid))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--extension-cpu-percent expects an integer from 1 to 100");
        }
        assertThatThrownBy(() -> parse("--extension-memory-mib", "384", "--extension-memory-mib", "512"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--extension-memory-mib may be supplied once");
        assertThatThrownBy(() -> parse("--extension-cpu-percent", "50", "--extension-cpu-percent", "100"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--extension-cpu-percent may be supplied once");
    }

    @Test
    void extensionTaskLimitRequiresTheCpuAndMemoryLimits() {
        assertThatThrownBy(() -> parse("--extension-max-tasks", "128"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--extension-max-tasks requires --extension-memory-mib and --extension-cpu-percent");
        for (String paired : List.of("--extension-memory-mib", "--extension-cpu-percent")) {
            assertThatThrownBy(() -> parse("--extension-max-tasks", "128", paired, "50"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--extension-max-tasks requires --extension-memory-mib and --extension-cpu-percent");
        }
        assertThatThrownBy(() -> parse("--extension-max-tasks", "128",
                "--extension-memory-mib", "384", "--extension-cpu-percent", "50"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("requires --require-extension-sandbox");
    }

    @Test
    void extensionTaskLimitRejectsMalformedOutOfRangeAndRepeatedValues() {
        for (String invalid : List.of("0", "-1", "2147483648", "1.5", "128tasks")) {
            assertThatThrownBy(() -> parse("--extension-max-tasks", invalid))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--extension-max-tasks expects an integer from 1");
        }
        assertThatThrownBy(() -> parse("--extension-max-tasks"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--extension-max-tasks needs a value");
        assertThatThrownBy(() -> parse("--extension-max-tasks", "128", "--extension-max-tasks", "64"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--extension-max-tasks may be supplied once");
    }

    @Test
    void linuxRunnerCarriesBothResourceLimitsWithItsApprovedAssertion() {
        String[] args = {"--extension-memory-mib", "384", "--require-extension-sandbox",
                "--isolated-assertion", "custom-check", "sha256:" + "a".repeat(64), "fixture.jar",
                "--extension-cpu-percent", "50"};
        if (System.getProperty("os.name").equals("Linux")) {
            var approval = parse(args).isolatedAssertion();
            assertThat(approval.requireOsSandbox()).isTrue();
            assertThat(approval.resourceLimits().memoryMiB()).isEqualTo(384);
            assertThat(approval.resourceLimits().cpuPercent()).isEqualTo(50);
            assertThat(approval.resourceLimits().maxTasks()).isNull();

            List<String> withMaxTasks = new java.util.ArrayList<>(List.of("--extension-max-tasks", "128"));
            withMaxTasks.addAll(List.of(args));
            var bounded = parse(withMaxTasks.toArray(String[]::new)).isolatedAssertion();
            assertThat(bounded.requireOsSandbox()).isTrue();
            assertThat(bounded.resourceLimits().memoryMiB()).isEqualTo(384);
            assertThat(bounded.resourceLimits().cpuPercent()).isEqualTo(50);
            assertThat(bounded.resourceLimits().maxTasks()).isEqualTo(128);
        } else {
            assertThatThrownBy(() -> parse(args))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--require-extension-sandbox requires Linux");
        }
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
