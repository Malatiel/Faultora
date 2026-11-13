package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.TlsMaterial;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;
import dev.faultora.testkit.Certificates;
import dev.faultora.testkit.QualificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class IsolatedAssertionE2ETest {
    @TempDir
    Path directory;

    @Test
    void packagedCliEvaluatesTheVerifiedProviderInAChildWithoutTheParentEnvironment() throws Exception {
        var artifact = fixture(directory, "0.1");
        var result = run(artifact, digest(artifact), "child-check");
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Result: PASSED", "1 passed assertions");
        assertThat(result.output()).doesNotContain("synthetic-parent-only");
    }

    @Test
    void packagedCliRejectsMismatchedDigestsAndIncompatibleManifests() throws Exception {
        var compatible = fixture(directory, "0.1");
        var wrongDigest = run(compatible, "sha256:" + "0".repeat(64), "child-check");
        assertThat(wrongDigest.exitCode()).as(wrongDigest.output()).isEqualTo(2);
        assertThat(wrongDigest.output()).contains("could not be verified").doesNotContain("Result: PASSED");

        var incompatible = fixture(directory, "99.0");
        var wrongVersion = run(incompatible, digest(incompatible), "child-check");
        assertThat(wrongVersion.exitCode()).as(wrongVersion.output()).isEqualTo(2);
        assertThat(wrongVersion.output()).contains("must support this Faultora version");
    }

    @Test
    void packagedCliRefusesAnExtensionThatWouldReplaceABuiltInType() throws Exception {
        var artifact = fixture(directory, "0.1");
        var result = run(artifact, digest(artifact), "status");
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains("already provided by Faultora");
    }

    @Test
    void dispatchedAssertionsUseOnlyTheOperatorsPinnedJar() throws Exception {
        var runner = Certificates.issue(directory, "runner", 1);
        var control = Certificates.issue(directory, "control", 1);
        var signing = Certificates.issue(directory, "signing", 1);
        var trustedControl = Certificates.trusting(directory, "runner", control);
        var controlTls = new TlsMaterial(control.keystore(), Certificates.trusting(directory, "control", runner),
                () -> Certificates.PASSWORD.toCharArray());
        Path artifact = fixture(directory, "0.1");

        try (var dispatcher = new QualificationDispatcher(controlTls.sslContext())) {
            dispatcher.offer(dispatch("approved-assertion", signing));
            var accepted = runRunner(dispatcher, runner, trustedControl, signing, artifact, digest(artifact));
            assertThat(accepted.exitCode()).as(accepted.output()).isZero();
            assertThat(dispatcher.outcomeOf("approved-assertion")).contains("PASSED");
            assertThat(dispatcher.journalOf("approved-assertion")).anyMatch(line -> line.contains("child-check"));

            dispatcher.offer(dispatch("changed-assertion", signing));
            var refused = runRunner(dispatcher, runner, trustedControl, signing, artifact, "sha256:" + "0".repeat(64));
            assertThat(refused.exitCode()).as(refused.output()).isEqualTo(3);
            assertThat(dispatcher.outcomeOf("changed-assertion")).contains("\"broke\":true");
            assertThat(refused.output()).doesNotContain("synthetic-parent-only");
        }
    }

    private static Dispatch dispatch(String runId, Certificates.Identity signing) throws Exception {
        String scenario = scenario("child-check");
        String policy = new ObjectMapper().writeValueAsString(EffectivePolicy.of(new TargetPolicy(
                Set.of(), Set.of(SafetyClassification.READ_ONLY),
                100, 2, 60_000, 1024, Set.of(), Set.of())));
        long now = System.currentTimeMillis();
        return new Dispatch(runId, now, runId + "-nonce", scenario, List.of(), Map.of(),
                Map.of("", "http://localhost:1"), Dispatch.Credentials.none(), 5,
                new SignedPolicy(policy, "qualification", Certificates.sign(signing, "RSA", policy)),
                new Lease(now, 120_000, 20_000), ContentDigest.sha256Uri(scenario),
                Dispatch.digestOfDocuments(List.of()));
    }

    private Result runRunner(QualificationDispatcher dispatcher, Certificates.Identity runner, Path trustedControl,
                             Certificates.Identity signing, Path artifact, String digest) throws Exception {
        Path work = Files.createTempDirectory(directory, "runner-");
        return launch(work, List.of("runner", "--dispatcher", dispatcher.address(),
                "--keystore", runner.keystore().toString(), "--truststore", trustedControl.toString(),
                "--tls-secret-id", "runner-tls", "--policy-key", "qualification=" + signing.certificate(),
                "--work-dir", work.resolve("spool").toString(), "--allow-private", "--once",
                "--isolated-assertion", "child-check", digest, artifact.toString()));
    }

    private record Result(int exitCode, String output) { }

    private Result run(Path artifact, String digest, String type) throws Exception {
        Path work = Files.createTempDirectory(directory, "invocation-");
        Path scenario = work.resolve("scenario.yaml");
        Files.writeString(scenario, scenario(type));
        return launch(work, List.of("test", "--scenario", scenario.toString(),
                "--isolated-assertion", type, digest, artifact.toString(),
                "--output", work.resolve("result").toString()));
    }

    static String scenario(String type) {
        return """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata:
                  name: isolated-assertion
                execute:
                  - id: pause
                    type: wait
                    timeout: 1ms
                assertions:
                  - id: child
                    assertionType: %s
                    targetStep: pause
                    params:
                      expected: synthetic
                """.formatted(type);
    }

    private Result launch(Path work, List<String> args) throws Exception {
        Path release = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        Path output = work.resolve("output.log");
        var invocation = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfaultora.test.parent=synthetic-parent-only",
                "-jar", release.toString()));
        invocation.addAll(args);
        ProcessBuilder command = new ProcessBuilder(invocation)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        command.environment().put("FAULTORA_TEST_PARENT_ONLY", "synthetic-parent-only");
        command.environment().put("FAULTORA_SECRET_RUNNER_TLS", Certificates.PASSWORD);
        var cli = command.start();
        try {
            assertThat(cli.waitFor(45, TimeUnit.SECONDS)).isTrue();
            return new Result(cli.exitValue(), Files.readString(output));
        } finally {
            if (cli.isAlive()) {
                cli.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    static Path fixture(Path directory, String minimumVersion) throws Exception {
        Path jar = Files.createTempFile(directory, "assertion-", ".jar");
        String name = ChildAssertion.class.getName();
        String entry = name.replace('.', '/') + ".class";
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
             var compiled = ChildAssertion.class.getResourceAsStream("/" + entry)) {
            assertThat(compiled).isNotNull();
            writeEntry(out, entry, compiled.readAllBytes());
            writeEntry(out, "META-INF/services/" + AssertionProvider.class.getName(),
                    name.getBytes(StandardCharsets.UTF_8));
            writeEntry(out, "META-INF/faultora-plugin.yaml", """
                    id: synthetic-assertion
                    version: "1.0"
                    requiresApi:
                      from: "%s"
                    """.formatted(minimumVersion).getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    static String digest(Path file) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    public static class ChildAssertion implements AssertionProvider {
        @Override
        public String type() {
            return "child-check";
        }

        @Override
        public AssertionResult evaluate(String type, Map<String, Object> params, EvidenceView evidence,
                                        AssertionContext context) {
            return System.getenv("FAULTORA_TEST_PARENT_ONLY") == null
                    && System.getenv("FAULTORA_SECRET_RUNNER_TLS") == null
                    && System.getProperty("faultora.test.parent") == null
                    && "synthetic".equals(params.get("expected"))
                    ? AssertionResult.pass("synthetic assertion")
                    : AssertionResult.fail("synthetic assertion failed", Map.of());
        }
    }
}
