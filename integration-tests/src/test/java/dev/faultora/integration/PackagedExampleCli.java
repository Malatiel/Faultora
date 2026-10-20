package dev.faultora.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the published example files through the executable users receive. */
final class PackagedExampleCli {
    private PackagedExampleCli() {
    }

    static int run(Path output, Path scenario, List<String> options) throws Exception {
        Files.createDirectories(output);
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        assertThat(jar).as("packaged CLI built earlier in the reactor").isRegularFile();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", jar.toString(),
                "test", "--scenario", scenario.toString(), "--allow-private",
                "--format", "console,json", "--output", output.toString()));
        command.addAll(options);
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(210, TimeUnit.SECONDS))
                    .as("example CLI finished: %s", scenario.getFileName()).isTrue();
            assertThat(process.exitValue()).as(Files.readString(stdout) + Files.readString(stderr)).isIn(0, 1);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("example CLI teardown").isTrue();
            }
        }
    }
}
