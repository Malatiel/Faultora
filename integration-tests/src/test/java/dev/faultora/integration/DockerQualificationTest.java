package dev.faultora.integration;

import dev.faultora.testkit.Qualification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the actual suite condition in separate JVMs without touching host Docker. */
class DockerQualificationTest {
    @Test
    void falseAndThrowingDockerProbesFailStrictProcessesButOnlySkipOptionalTests() throws Exception {
        for (String response : new String[]{"missing", "throws"}) {
            for (boolean strict : new boolean[]{false, true}) {
                Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-D" + Qualification.STRICT_PROPERTY + "=" + strict, "-cp",
                        System.getProperty("java.class.path"), DockerQualificationTest.class.getName(), response)
                        .redirectErrorStream(true).start();
                try {
                    assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(output).contains("Qualification prerequisite unavailable: Docker")
                            .doesNotContain("synthetic-probe-detail");
                    if (strict) {
                        assertThat(process.exitValue()).isNotZero();
                        assertThat(output).doesNotContain("optional local tests skipped");
                    } else {
                        assertThat(process.exitValue()).isZero();
                        assertThat(output).contains("optional local tests skipped");
                    }
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        RecoveryInfrastructure.dockerIsAvailable(() -> {
            if (args[0].equals("throws")) {
                throw new IllegalStateException("synthetic-probe-detail");
            }
            return false;
        });
    }
}

/** Selected explicitly for a Maven/JUnit failure-versus-skip check, never by default test discovery. */
@org.junit.jupiter.api.condition.EnabledIf("dockerIsAvailable")
class MissingDockerFixture {
    static boolean dockerIsAvailable() {
        return RecoveryInfrastructure.dockerIsAvailable(() -> false);
    }

    @Test
    void requiresDocker() {
        throw new AssertionError("An unavailable prerequisite must never execute its test");
    }
}
