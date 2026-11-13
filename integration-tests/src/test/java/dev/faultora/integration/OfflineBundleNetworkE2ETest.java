package dev.faultora.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A transferred CLI bundle verifies and starts with only the destination's Java runtime. */
@EnabledOnOs(OS.LINUX)
class OfflineBundleNetworkE2ETest {
    @TempDir
    Path directory;

    @Test
    void theTransferredBundleVerifiesAndRunsWithoutExternalNetworkAccess() throws Exception {
        Path bwrap = Path.of("/usr/bin/bwrap");
        assumeTrue(Boolean.getBoolean("faultora.qualification.strict") || Files.isExecutable(bwrap),
                "Bubblewrap is optional outside strict qualification");
        assertThat(bwrap).isExecutable();

        Path project = Path.of(System.getProperty("faultora.projectRoot"));
        String version = System.getProperty("faultora.version");
        Path outbound = Files.createDirectory(directory.resolve("outbound"));
        Path inbound = Files.createDirectory(directory.resolve("inbound"));
        Path logs = Files.createDirectory(directory.resolve("logs"));
        var built = ImageCommand.run(project, logs, 120, "env", "OUT=" + outbound,
                "IMAGE=faultora/runner:offline-test-" + UUID.randomUUID(), "sh",
                project.resolve("deploy/offline-bundle.sh").toString(), version);
        assertThat(built.exitValue()).as(built.output()).isZero();
        var match = Pattern.compile("SHA256SUMS fingerprint: ([0-9a-f]{64})").matcher(built.output());
        assertThat(match.find()).isTrue();

        String folder = "faultora-" + version + "-offline";
        String archive = folder + ".tar.gz";
        Files.copy(outbound.resolve(archive), inbound.resolve(archive));
        String parentNet = Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString();

        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        var verified = ImageCommand.run(directory, logs, 120,
                bwrap.toString(), "--unshare-user", "--unshare-net", "--unshare-pid", "--disable-userns",
                "--cap-drop", "ALL", "--new-session", "--die-with-parent", "--clearenv",
                "--ro-bind", "/usr", "/usr", "--ro-bind", "/bin", "/bin",
                "--ro-bind", "/lib", "/lib", "--ro-bind-try", "/lib64", "/lib64",
                "--ro-bind", javaHome.toString(), "/java",
                "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
                "--bind", inbound.toString(), "/transfer",
                "--chdir", "/transfer", "--setenv", "PATH", "/java/bin:/usr/bin:/bin",
                "--setenv", "LD_LIBRARY_PATH", "/java/lib:/java/lib/server",
                "--", "/bin/sh", "-ec", """
                        test "$(readlink /proc/self/ns/net)" != "$1"
                        awk 'NR > 1 { exit 1 }' /proc/net/route
                        tar -xzf "$2"
                        sh "$3/verify-offline-bundle.sh" --fingerprint "$4"
                        """, "offline-bundle-receiver", parentNet, archive, folder, match.group(1));
        assertThat(verified.exitValue()).as(verified.output()).isZero();
        assertThat(verified.output()).contains("Bundle " + version + " verified", "and the CLI runs.");
    }
}
