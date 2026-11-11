package dev.faultora.integration;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class IsolatedAssertionE2ETest {
    @TempDir
    Path directory;

    @Test
    void packagedCliEvaluatesTheVerifiedProviderInAChildWithoutTheParentEnvironment() throws Exception {
        var artifact = fixture("0.1");
        var result = run(artifact, digest(artifact), "child-check");
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Result: PASSED", "1 passed assertions");
        assertThat(result.output()).doesNotContain("synthetic-parent-only");
    }

    @Test
    void packagedCliRejectsMismatchedDigestsAndIncompatibleManifests() throws Exception {
        var compatible = fixture("0.1");
        var wrongDigest = run(compatible, "sha256:" + "0".repeat(64), "child-check");
        assertThat(wrongDigest.exitCode()).as(wrongDigest.output()).isEqualTo(2);
        assertThat(wrongDigest.output()).contains("could not be verified").doesNotContain("Result: PASSED");

        var incompatible = fixture("99.0");
        var wrongVersion = run(incompatible, digest(incompatible), "child-check");
        assertThat(wrongVersion.exitCode()).as(wrongVersion.output()).isEqualTo(2);
        assertThat(wrongVersion.output()).contains("must support this Faultora version");
    }

    @Test
    void packagedCliRefusesAnExtensionThatWouldReplaceABuiltInType() throws Exception {
        var artifact = fixture("0.1");
        var result = run(artifact, digest(artifact), "status");
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains("already provided by Faultora");
    }

    private record Result(int exitCode, String output) { }

    private Result run(Path artifact, String digest, String type) throws Exception {
        Path work = Files.createTempDirectory(directory, "invocation-");
        Path scenario = work.resolve("scenario.yaml");
        Files.writeString(scenario, """
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
                """.formatted(type));
        Path release = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        Path output = work.resolve("output.log");
        ProcessBuilder command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfaultora.test.parent=synthetic-parent-only",
                "-jar", release.toString(), "test", "--scenario", scenario.toString(),
                "--isolated-assertion", type, digest, artifact.toString(),
                "--output", work.resolve("result").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        command.environment().put("FAULTORA_TEST_PARENT_ONLY", "synthetic-parent-only");
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

    private Path fixture(String minimumVersion) throws Exception {
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

    private static String digest(Path file) throws Exception {
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
                    && System.getProperty("faultora.test.parent") == null
                    && "synthetic".equals(params.get("expected"))
                    ? AssertionResult.pass("synthetic assertion")
                    : AssertionResult.fail("synthetic assertion failed", Map.of());
        }
    }
}
