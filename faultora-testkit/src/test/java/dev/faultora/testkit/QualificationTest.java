package dev.faultora.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualificationTest {
    @Test
    void unavailableDockerMakesAStrictProcessFailWhileTheDefaultProcessCanSkip() throws Exception {
        for (boolean strict : new boolean[]{false, true}) {
            Process process = new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-D" + Qualification.STRICT_PROPERTY + "=" + strict, "-cp",
                    System.getProperty("java.class.path"), Probe.class.getName())
                    .redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertThat(output).contains("Qualification prerequisite unavailable: Docker");
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

    public static final class Probe {
        public static void main(String[] args) {
            Qualification.available(false, "Docker");
        }
    }

    @Test
    void missingRequiredRuntimeFailsRatherThanDisablingTests() {
        assertThatThrownBy(() -> Qualification.available(false, "Docker", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Qualification prerequisite unavailable: Docker");
        assertThatThrownBy(() -> Qualification.available(false, "lsof", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Qualification prerequisite unavailable: lsof");
    }

    @Test
    void availableRuntimeEnablesBothModesAndOptionalRuntimeMaySkip() {
        assertThat(Qualification.available(true, "Docker", true)).isTrue();
        assertThat(Qualification.available(true, "Docker", false)).isTrue();
        assertThat(Qualification.available(false, "Docker", false)).isFalse();
    }
}
