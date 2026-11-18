package dev.faultora.extension.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LinuxAssertionScopeTest {
    private static final ExtensionResourceLimits LIMITS = new ExtensionResourceLimits(128, 50);
    private static final String MEMBERSHIP = "0::/app.slice/example.scope";
    @TempDir Path directory;

    @Test
    void resourceRangesUsePositiveMiBAndWholePercentOfOneCpu() {
        assertThat(new ExtensionResourceLimits(1, 1).memoryBytes()).isEqualTo(1048576);
        assertThat(new ExtensionResourceLimits(Integer.MAX_VALUE, 100).memoryBytes()).isEqualTo(2251799812636672L);
        assertThat(new ExtensionResourceLimits(1, 100).quotaMicros()).isEqualTo(100000);
        assertThatIllegalArgumentException().isThrownBy(() -> new ExtensionResourceLimits(0, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExtensionResourceLimits(-1, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExtensionResourceLimits(128, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExtensionResourceLimits(128, 101));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void launchesOnlyWhenMembershipAndKernelLimitsMatch() throws Exception {
        Path group = writeGroup();
        assertThat(run(group, MEMBERSHIP)).isZero();
        assertThat(Files.readString(directory.resolve("launched.txt"))).isEqualTo("a literal $ARG; remains one argument");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "memory.max:max", "memory.max:134221824", "memory.swap.max:1",
            "cpu.max:max 100000", "cpu.max:50000 50000", "cpu.max:60000 100000",
            "cpu.max.burst:1000", "cpu.max:<missing>"
    }, delimiter = ':')
    @EnabledOnOs(OS.LINUX)
    void refusesMissingUnlimitedOrWrongSettingsWithoutStartingTheCommand(String setting, String observed)
            throws Exception {
        Path group = writeGroup();
        Path file = group.resolve(setting);
        if (observed.equals("<missing>")) {
            Files.delete(file);
        } else {
            Files.writeString(file, observed + "\n");
        }
        assertThat(run(group, MEMBERSHIP)).isEqualTo(125);
        assertThat(directory.resolve("launched.txt")).doesNotExist();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusesAnotherScopeEvenWhenTheExpectedGroupHasCorrectSettings() throws Exception {
        assertThat(run(writeGroup(), "0::/app.slice/other.scope")).isEqualTo(125);
        assertThat(directory.resolve("launched.txt")).doesNotExist();
    }

    private Path writeGroup() throws Exception {
        Path group = Files.createDirectory(directory.resolve("example.scope"));
        Files.writeString(group.resolve("memory.max"), "134217728\n");
        Files.writeString(group.resolve("memory.swap.max"), "0\n");
        Files.writeString(group.resolve("cpu.max"), "50000 100000\n");
        Files.writeString(group.resolve("cpu.max.burst"), "0\n");
        return group;
    }

    private int run(Path group, String actual) throws Exception {
        Path membership = Files.writeString(directory.resolve("membership"), actual + "\n");
        List<String> inert = List.of("/bin/sh", "-ec", "printf '%s' \"$1\" > launched.txt",
                "assertion-sandbox-fixture", "a literal $ARG; remains one argument");
        var builder = new ProcessBuilder(LinuxAssertionScope.guardCommand(membership, MEMBERSHIP, group, LIMITS, inert))
                .directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
