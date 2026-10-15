package dev.faultora.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerImageBuildArgumentTest {

    @TempDir
    Path directory;

    @Test
    void theJarArgumentUsesDockerContextSeparators() {
        Path jar = directory.resolve("faultora-cli").resolve("target")
                .resolve("faultora-0.10.1.jar");

        assertThat(RunnerImageE2ETest.jarBuildArgument(directory, jar))
                .isEqualTo("JAR=faultora-cli/target/faultora-0.10.1.jar");
    }

    @Test
    void spacesRemainPartOfTheArgumentRatherThanShellQuoting() {
        Path root = directory.resolve("build context");
        Path jar = root.resolve("packaged cli").resolve("faultora release.jar");

        assertThat(RunnerImageE2ETest.jarBuildArgument(root, jar))
                .isEqualTo("JAR=packaged cli/faultora release.jar");
    }
}
