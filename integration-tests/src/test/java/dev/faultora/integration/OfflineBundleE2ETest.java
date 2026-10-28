package dev.faultora.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline bundle as its recipient meets it: built by the published script,
 * unpacked somewhere new, and checked by the script it carries before anything
 * in it is run. Each way a bundle can be altered on its way across is made once,
 * and each has to be refused before the bundled jar is started — which a
 * {@code java} on {@code PATH} that records every start makes observable.
 */
@EnabledOnOs(OS.LINUX)
class OfflineBundleE2ETest {
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final String VERSION = System.getProperty("faultora.version");

    @TempDir
    Path directory;

    private Path archive;
    private String fingerprint;
    private Path starts;

    @BeforeEach
    void build() throws Exception {
        Path out = Files.createDirectory(directory.resolve("out"));
        // A name no image has, so the bundle is the jar alone whatever this
        // machine has built: the image is a docker save, not something checked here.
        Result built = run(List.of("sh", ROOT.resolve("deploy/offline-bundle.sh").toString(), VERSION), ROOT,
                "OUT", out.toString(), "IMAGE", "faultora/runner:not-built-" + UUID.randomUUID());
        assertThat(built.code()).as(built.output()).isZero();
        Matcher printed = Pattern.compile("SHA256SUMS fingerprint: ([0-9a-f]{64})").matcher(built.output());
        assertThat(printed.find()).as(built.output()).isTrue();
        fingerprint = printed.group(1);
        archive = out.resolve("faultora-" + VERSION + "-offline.tar.gz");
        assertThat(archive).isRegularFile();
        starts = directory.resolve("java-starts");
    }

    @Test
    void aFreshlyUnpackedBundleWithItsRecordedFingerprintVerifiesAndItsCliRuns() throws Exception {
        Path bundle = unpack();
        assertThat(sha256(bundle.resolve("SHA256SUMS"))).as("the printed fingerprint").isEqualTo(fingerprint);

        Result verified = verify(bundle, null, "--fingerprint", fingerprint);
        assertThat(verified.code()).as(verified.output()).isZero();
        assertThat(verified.output()).contains("Bundle " + VERSION + " verified", "not compared with a release");
        assertThat(javaStarts()).as("--version and validate").isEqualTo(2);

        Path releaseSums = releaseSums(sha256(bundle.resolve("faultora.jar")));
        Result attested = verify(bundle, null, "--fingerprint", fingerprint, "--release-sums", releaseSums.toString());
        assertThat(attested.code()).as(attested.output()).isZero();
        assertThat(attested.output()).doesNotContain("not compared with a release");
    }

    @Test
    void withoutAFingerprintNothingIsTrustedOrRun() throws Exception {
        Path bundle = unpack();
        Result refused = verify(bundle, null);
        assertThat(refused.code()).as(refused.output()).isNotZero();
        assertThat(refused.output()).contains("no --fingerprint");

        Result selfOnly = verify(bundle, null, "--self-consistency-only");
        assertThat(selfOnly.code()).as(selfOnly.output()).isZero();
        assertThat(selfOnly.output()).contains("NOT a trust check").doesNotContain("verified");
        assertThat(javaStarts()).isZero();
    }

    enum Tampering {
        MODIFIED_FILE("files do not match SHA256SUMS"),
        MISSING_FILE("listed in SHA256SUMS but missing: ./NOTICE"),
        ADDED_FILE("not listed in SHA256SUMS: ./deploy/extra.sh"),
        LINK("contains a link or special file"),
        PATH_OUTSIDE("SHA256SUMS names a path outside the bundle"),
        UNLISTED_REQUIRED_FILE("a complete bundle has ./LICENSE");

        final String refusal;

        Tampering(String refusal) {
            this.refusal = refusal;
        }
    }

    /** Checked against the bundle's own list, where only the list's contents can catch them. */
    @ParameterizedTest
    @EnumSource(Tampering.class)
    void anAlteredBundleIsRefusedBeforeItsJarRuns(Tampering tampering) throws Exception {
        Path bundle = unpack();
        Path sums = bundle.resolve("SHA256SUMS");
        switch (tampering) {
            case MODIFIED_FILE -> Files.writeString(bundle.resolve("LICENSE"), "\n", StandardOpenOption.APPEND);
            case MISSING_FILE -> Files.delete(bundle.resolve("NOTICE"));
            case ADDED_FILE -> Files.writeString(bundle.resolve("deploy/extra.sh"), "echo planted\n");
            case LINK -> Files.createSymbolicLink(bundle.resolve("deploy/link"), Path.of("../faultora.jar"));
            case PATH_OUTSIDE -> Files.writeString(sums, "0".repeat(64) + "  ./deploy/../../outside\n",
                    StandardOpenOption.APPEND);
            case UNLISTED_REQUIRED_FILE -> {
                Files.delete(bundle.resolve("LICENSE"));
                rewriteSums(bundle, line -> !line.endsWith("  ./LICENSE"), line -> line);
            }
        }
        // The fingerprint of whatever list arrived, so that only the checks
        // after it are exercised; a recorded one would refuse the edits above first.
        Result refused = verify(bundle, null, "--fingerprint", sha256(sums));
        assertThat(refused.code()).as(refused.output()).isNotZero();
        assertThat(refused.output()).contains(tampering.refusal).doesNotContain("verified");
        assertThat(javaStarts()).isZero();
    }

    @Test
    void aReplacedJarWithRewrittenChecksumsIsRefusedBeforeItRuns() throws Exception {
        Path bundle = unpack();
        String released = sha256(bundle.resolve("faultora.jar"));
        // A different jar that still runs: the bytes go in as the archive
        // comment, so only something from outside the bundle can tell the two apart.
        plantComment(bundle.resolve("faultora.jar"), "planted");
        String replaced = sha256(bundle.resolve("faultora.jar"));
        rewriteSums(bundle, line -> true, line -> line.endsWith("  ./faultora.jar")
                ? replaced + "  ./faultora.jar" : line);

        Result byFingerprint = verify(bundle, null, "--fingerprint", fingerprint);
        assertThat(byFingerprint.code()).as(byFingerprint.output()).isNotZero();
        assertThat(byFingerprint.output()).contains("not the recorded " + fingerprint, "nothing here was run");
        assertThat(javaStarts()).isZero();

        Result byRelease = verify(bundle, null, "--fingerprint", sha256(bundle.resolve("SHA256SUMS")),
                "--release-sums", releaseSums(released).toString());
        assertThat(byRelease.code()).as(byRelease.output()).isNotZero();
        assertThat(byRelease.output()).contains("faultora.jar is not the released faultora-" + VERSION + ".jar");
        assertThat(javaStarts()).isZero();
    }

    @Test
    void aJarReportingAVersionThatOnlyStartsWithTheBundlesIsRefused() throws Exception {
        Path bundle = unpack();
        Result refused = verify(bundle, "faultora " + VERSION + "0", "--fingerprint", fingerprint);
        assertThat(refused.code()).as(refused.output()).isNotZero();
        assertThat(refused.output()).contains("reports 'faultora " + VERSION + "0', not faultora " + VERSION);
    }

    private Path unpack() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("arrived-" + UUID.randomUUID()));
        Result unpacked = run(List.of("tar", "-xzf", archive.toString(), "-C", destination.toString()), destination);
        assertThat(unpacked.code()).as(unpacked.output()).isZero();
        return destination.resolve("faultora-" + VERSION + "-offline");
    }

    /**
     * Run the bundled verifier from elsewhere, with a {@code java} on {@code PATH}
     * that counts its starts and then runs this JVM's java — or, given
     * {@code reportedVersion}, answers {@code --version} with it instead.
     */
    private Result verify(Path bundle, String reportedVersion, String... arguments) throws Exception {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, """
                #!/bin/sh
                echo started >> "$JAVA_STARTS"
                if [ -n "${REPORTED_VERSION:-}" ] && [ "$3" = "--version" ]; then
                    echo "$REPORTED_VERSION"
                    exit 0
                fi
                exec "$REAL_JAVA" "$@"
                """);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<String> command = new ArrayList<>(List.of("sh", bundle.resolve("verify-offline-bundle.sh").toString()));
        command.addAll(List.of(arguments));
        return run(command, directory, "PATH", bin + ":/usr/bin:/bin", "JAVA_STARTS", starts.toString(),
                "REAL_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "REPORTED_VERSION", reportedVersion == null ? "" : reportedVersion);
    }

    private long javaStarts() throws IOException {
        return Files.exists(starts) ? Files.readAllLines(starts).size() : 0;
    }

    private Path releaseSums(String jarDigest) throws IOException {
        Path sums = directory.resolve("release-SHA256SUMS-" + UUID.randomUUID());
        Files.writeString(sums, "0".repeat(64) + "  faultora-" + VERSION + ".zip\n"
                + jarDigest + "  faultora-" + VERSION + ".jar\n");
        return sums;
    }

    private static void rewriteSums(Path bundle, Predicate<String> keep, UnaryOperator<String> change)
            throws IOException {
        Path sums = bundle.resolve("SHA256SUMS");
        List<String> lines = Files.readAllLines(sums).stream().filter(keep).map(change).toList();
        Files.write(sums, lines);
    }

    /** Give an archive that has no comment the comment {@code text}. */
    private static void plantComment(Path archive, String text) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        int end = bytes.length - 22;
        assertThat(end).isPositive();
        assertThat(new byte[] {bytes[end], bytes[end + 1], bytes[end + 2], bytes[end + 3]})
                .as("end of central directory, with no comment yet").containsExactly(0x50, 0x4b, 0x05, 0x06);
        byte[] comment = text.getBytes(StandardCharsets.US_ASCII);
        bytes[end + 20] = (byte) comment.length;
        bytes[end + 21] = 0;
        Files.write(archive, bytes);
        Files.write(archive, comment, StandardOpenOption.APPEND);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private Result run(List<String> command, Path work, String... environment) throws Exception {
        Path log = Files.createTempFile(directory, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        for (int i = 0; i < environment.length; i += 2) {
            builder.environment().put(environment[i], environment[i + 1]);
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("finished: %s", command).isTrue();
            return new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private record Result(int code, String output) { }
}
