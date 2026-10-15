package dev.faultora.runtime;

import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.extension.PluginManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginArtifactTest {

    private static final String VALID_MANIFEST = """
            id: synthetic-plugin
            version: 1.0
            requiresApi:
              from: 0.1
            """;

    @TempDir
    Path directory;

    private enum Layout { JAR, DIRECTORY }

    @Test
    void theStreamingDigestIdentifiesEveryByteOfTheJar() throws Exception {
        byte[] padding = new byte[24 * 1024];
        new java.util.Random(1).nextBytes(padding);
        Path jar = artifact(Layout.JAR, VALID_MANIFEST + "#"
                + java.util.Base64.getEncoder().encodeToString(padding));
        assertThat(Files.size(jar)).isGreaterThan(8192);
        PluginArtifact.Inspection inspected = inspect(jar);

        assertThat(inspected.refusal()).isNull();
        assertThat(inspected.artifact().digest()).isEqualTo(ContentDigest.sha256Uri(Files.readAllBytes(jar)));
        assertThat(inspected.artifact().manifest().id()).isEqualTo("synthetic-plugin");
    }

    @Test
    void theArtifactSizeBoundaryCanBeHashedWithoutAllocatingTheArtifact() throws Exception {
        Path file = directory.resolve("boundary.jar");
        try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(PluginArtifact.MAX_ARTIFACT_BYTES);
        }

        PluginArtifact.Inspection inspected = inspect(file);

        assertThat(inspected.artifact().isIdentifiable()).isTrue();
        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("cannot be read");

        Path log = directory.resolve("digest.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                DigestProbe.class.getName(), file.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("the bounded digest finishes").isTrue();
            try (InputStream input = Files.newInputStream(log)) {
                String output = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(output).isZero();
                assertThat(output).contains(inspected.artifact().digest());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void anArtifactOneByteOverTheLimitIsRefusedWithoutAnIdentity() throws Exception {
        Path file = directory.resolve("oversized.jar");
        try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(PluginArtifact.MAX_ARTIFACT_BYTES + 1);
        }

        PluginArtifact.Inspection inspected = inspect(file);

        assertThat(inspected.artifact().digest()).isNull();
        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("artifact exceeds maximum size",
                Long.toString(PluginArtifact.MAX_ARTIFACT_BYTES));
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void aManifestExactlyAtTheByteLimitRemainsValid(Layout layout) throws Exception {
        String manifest = paddedManifest(PluginArtifact.MAX_MANIFEST_BYTES);
        PluginArtifact.Inspection inspected = inspect(artifact(layout, manifest));

        assertThat(inspected.refusal()).isNull();
        assertThat(inspected.artifact().manifest().id()).isEqualTo("synthetic-plugin");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void theManifestLimitCountsDecodedArchiveBytesRatherThanCharactersOrCompressedSize(Layout layout)
            throws Exception {
        String manifest = paddedManifest(PluginArtifact.MAX_MANIFEST_BYTES + 1);
        Path artifact = artifact(layout, manifest);
        if (layout == Layout.JAR) {
            assertThat(Files.size(artifact)).isLessThan(PluginArtifact.MAX_MANIFEST_BYTES);
        }

        PluginArtifact.Inspection inspected = inspect(artifact);

        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("manifest exceeds maximum size",
                Integer.toString(PluginArtifact.MAX_MANIFEST_BYTES));
    }

    private static String paddedManifest(int bytes) {
        String prefix = VALID_MANIFEST + "#";
        int remaining = bytes - prefix.getBytes(StandardCharsets.UTF_8).length;
        String manifest = prefix + "я".repeat(remaining / 2) + "x".repeat(remaining % 2);
        assertThat(manifest.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        return manifest;
    }

    static Stream<String> invalidManifests() {
        return Stream.of(
                "",
                "null\n",
                "requiresApi: [unterminated\n",
                VALID_MANIFEST + "version: 2.0\n",
                VALID_MANIFEST + "---\n" + VALID_MANIFEST,
                VALID_MANIFEST.replace("id: synthetic-plugin\n", ""),
                VALID_MANIFEST.replace("version: 1.0", "version: ''"),
                "id: synthetic-plugin\nversion: 1.0\n",
                VALID_MANIFEST.replace("from: 0.1", "from: null"),
                VALID_MANIFEST.replace("from: 0.1", "from: 0..1"),
                VALID_MANIFEST.replace("from: 0.1", "from: 2147483648.0"),
                VALID_MANIFEST.replace("from: 0.1", "from: invalid"),
                VALID_MANIFEST.replace("from: 0.1", "from: " + "0.".repeat(1000) + "1"),
                VALID_MANIFEST.replace("from: 0.1", "from: 1.0\n  to: 0.1"),
                VALID_MANIFEST + "capabilities:\n  networkDestinations: [null]\n",
                VALID_MANIFEST + "unexpectedField: value\n",
                VALID_MANIFEST.replace("from: 0.1", "from: " + "[".repeat(32) + "0" + "]".repeat(32)));
    }

    @ParameterizedTest
    @MethodSource("invalidManifests")
    void malformedMetadataIsRefusedWithoutEchoingItsContents(String manifest) throws Exception {
        PluginArtifact.Inspection inspected = inspect(artifact(Layout.JAR, manifest));

        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("manifest").doesNotContain("synthetic-plugin", "unexpectedField");
    }

    static Stream<String> validRanges() {
        return Stream.of("from: 0.1", "to: 1.0", "from: 0.1\n  to: 1.0",
                "from: 0.10.1-SNAPSHOT", "from: 0.10.1+build.1");
    }

    @ParameterizedTest
    @MethodSource("validRanges")
    void supportedVersionBoundsRemainValid(String range) throws Exception {
        PluginArtifact.Inspection inspected = inspect(artifact(Layout.JAR,
                VALID_MANIFEST.replace("from: 0.1", range)));

        assertThat(inspected.refusal()).isNull();
        assertThat(inspected.artifact().manifest().requiresApi()).isNotNull();
    }

    @Test
    void aParentLoadersManifestDoesNotDescribeTheProvidersDirectory() throws Exception {
        Path foreign = artifact(Layout.DIRECTORY, VALID_MANIFEST);
        Path own = Files.createDirectory(directory.resolve("own-classes"));
        try (URLClassLoader parent = new URLClassLoader(new URL[]{foreign.toUri().toURL()},
                PluginArtifactTest.class.getClassLoader())) {
            Class<?> type = providerAt(own, parent);

            try (InputStream inherited = type.getClassLoader().getResourceAsStream(PluginManifest.LOCATION)) {
                assertThat(inherited).isNotNull();
            }
            assertThat(PluginArtifact.of(type).manifest()).isNull();
        }
    }

    @Test
    void unsupportedOriginsAreRefusedWithoutFetchingAResource() throws Exception {
        Class<?> type = providerAt(new URL("https://127.0.0.1/synthetic-plugin.jar"),
                PluginArtifactTest.class.getClassLoader());

        PluginArtifact.Inspection inspected = PluginArtifact.inspect(type);

        assertThat(inspected.artifact().digest()).isNull();
        assertThat(inspected.refusal()).contains("not a local file");
    }

    @Test
    void anUnavailableArtifactProducesARefusalInsteadOfCrashingDiscovery() throws Exception {
        PluginArtifact.Inspection inspected = inspect(directory.resolve("missing.jar"));

        assertThat(inspected.artifact().digest()).isNull();
        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("not a readable regular file");
    }

    @Test
    void aDirectoryAtTheManifestPathIsRefusedBeforeOpeningIt() throws Exception {
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Files.createDirectories(classes.resolve(PluginManifest.LOCATION));

        PluginArtifact.Inspection inspected = inspect(classes);

        assertThat(inspected.artifact().manifest()).isNull();
        assertThat(inspected.refusal()).contains("manifest is not a regular file");
    }

    private Path artifact(Layout layout, String manifest) throws Exception {
        if (layout == Layout.DIRECTORY) {
            Path classes = Files.createDirectory(directory.resolve("classes"));
            Path declared = classes.resolve(PluginManifest.LOCATION);
            Files.createDirectories(declared.getParent());
            Files.writeString(declared, manifest);
            return classes;
        }
        Path jar = directory.resolve("plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(PluginManifest.LOCATION));
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static PluginArtifact.Inspection inspect(Path artifact) throws Exception {
        return PluginArtifact.inspect(providerAt(artifact, PluginArtifactTest.class.getClassLoader()));
    }

    private static Class<?> providerAt(Path artifact, ClassLoader parent) throws Exception {
        return providerAt(artifact.toUri().toURL(), parent);
    }

    private static Class<?> providerAt(URL origin, ClassLoader parent) throws Exception {
        String name = Probe.class.getName();
        byte[] bytes;
        try (InputStream source = PluginArtifactTest.class.getResourceAsStream("PluginArtifactTest$Probe.class")) {
            assertThat(source).isNotNull();
            bytes = source.readAllBytes();
        }
        class ProviderLoader extends SecureClassLoader {
            ProviderLoader() {
                super(parent);
            }

            Class<?> provider() {
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(origin, (Certificate[]) null));
            }
        }
        return new ProviderLoader().provider();
    }

    private static class Probe {
    }

    /** Hash a larger-than-heap artifact in an actual child JVM. */
    public static class DigestProbe {
        public static void main(String[] args) throws Exception {
            PluginArtifact artifact = PluginArtifact.of(providerAt(Path.of(args[0]),
                    PluginArtifactTest.class.getClassLoader()));
            if (!artifact.isIdentifiable()) {
                throw new IllegalStateException("The boundary artifact must have a digest");
            }
            System.out.println(artifact.digest());
        }
    }
}
