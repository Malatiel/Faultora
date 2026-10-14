package dev.faultora.integration;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Unpermitted services stay inert even before the release jar on the classpath. */
class PluginDiscoveryE2ETest {

    @TempDir
    Path directory;

    static Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of("dev.faultora.untrusted.OutsideProvider", false),
                Arguments.of("dev.faultora.assertions.core.StatusAssertionProvider", false),
                Arguments.of("dev.faultora.assertions.core.StatusAssertionProvider", true));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void thePackagedCliRejectsAnUnpermittedProviderBeforeItsCodeRuns(String className, boolean copySpi)
            throws Exception {
        Path release = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().normalize();
        assertThat(release).as("package the CLI before running its E2E suite").isRegularFile();
        Path plugin = pluginJar(release, className, copySpi);
        Path marker = directory.resolve("plugin-executed.txt");
        Path scenario = directory.resolve("wait.yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata:
                  name: plugin-discovery
                execute:
                  - id: pause
                    type: wait
                    timeout: 1ms
                """);
        Path log = directory.resolve("cli.log");
        Path reports = directory.resolve("reports");
        ProcessBuilder command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", plugin + File.pathSeparator + release,
                "dev.faultora.cli.FaultoraCli", "test",
                "--scenario", scenario.toString(),
                "--target", "http://127.0.0.1:1", "--allow-private",
                "--format", "console,json,junit,html", "--output", reports.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("FAULTORA_TEST_PLUGIN_MARKER", marker.toString());
        Process process = command.start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("the CLI finishes").isTrue();
            String said;
            try (var input = Files.newInputStream(log)) {
                said = new String(input.readNBytes(65_536), StandardCharsets.UTF_8);
            }
            assertThat(process.exitValue()).as(said).isZero();
            assertThat(said).contains("Refused assertion provider " + className);
            assertThat(marker).as("neither static initialization nor construction ran").doesNotExist();
            assertThat(reports.resolve("report.json")).isRegularFile();
            assertThat(reports.resolve("report.xml")).isRegularFile();
            assertThat(reports.resolve("report.html")).isRegularFile();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private Path pluginJar(Path release, String className, boolean copySpi) throws Exception {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Path source = classes.resolve(simpleName + ".java");
        Files.writeString(source, """
                package %s;
                public class %s implements dev.faultora.spi.contract.AssertionProvider {
                    static { mark("initialized"); }
                    public %s() { mark("constructed"); }
                    private static void mark(String value) {
                        try {
                            java.nio.file.Files.writeString(
                                    java.nio.file.Path.of(System.getenv("FAULTORA_TEST_PLUGIN_MARKER")),
                                    value, java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND);
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    public String type() { return "outside"; }
                    public dev.faultora.spi.result.AssertionResult evaluate(String type,
                            java.util.Map<String, Object> params,
                            dev.faultora.spi.context.EvidenceView evidence,
                            dev.faultora.spi.context.AssertionContext context) {
                        return dev.faultora.spi.result.AssertionResult.pass("synthetic");
                    }
                }
                """.formatted(className.substring(0, className.lastIndexOf('.')),
                simpleName, simpleName));
        ByteArrayOutputStream complaints = new ByteArrayOutputStream();
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, complaints, "-cp", release.toString(),
                "-d", classes.toString(), source.toString()))
                .as(() -> "fixture compilation: " + complaints).isZero();
        Path jar = directory.resolve("plugin.jar");
        String classEntry = className.replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(classEntry));
            output.write(Files.readAllBytes(classes.resolve(classEntry)));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/services/dev.faultora.spi.contract.AssertionProvider"));
            output.write(className.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (copySpi) {
                String spiEntry = "dev/faultora/spi/contract/AssertionProvider.class";
                try (JarFile shipped = new JarFile(release.toFile());
                     var input = shipped.getInputStream(shipped.getJarEntry(spiEntry))) {
                    output.putNextEntry(new JarEntry(spiEntry));
                    output.write(input.readAllBytes());
                    output.closeEntry();
                }
            }
        }
        return jar;
    }
}
