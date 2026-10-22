package dev.faultora.integration;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the assembled distribution without the reactor's classpath or working directory. */
class CliArchiveE2ETest {
    private static final String PATH_LABEL = nativePathLabel();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"zip", "tar.gz"})
    void extractedArchiveRunsFromAnotherDirectoryWithJavaHome(String format) throws Exception {
        Path home = unpack(format);
        checkContents(home);
        checkCommands(home, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zip", "tar.gz"})
    void launcherAlsoFindsJavaOnPath(String format) throws Exception {
        checkCommands(unpack(format), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zip", "tar.gz"})
    @EnabledOnOs(OS.LINUX)
    void launcherResolvesAbsoluteAndRelativeSymbolicLinks(String format) throws Exception {
        Path home = unpack(format);
        Path links = Files.createDirectory(directory.resolve("linked launchers with spaces"));
        Path absolute = Files.createSymbolicLink(links.resolve("absolute"), home.resolve("bin/faultora"));
        Path relative = Files.createSymbolicLink(links.resolve("relative"), Path.of("absolute"));
        checkCommands(home, false, absolute);
        checkCommands(home, false, relative);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zip", "tar.gz"})
    @EnabledOnOs(OS.LINUX)
    void shellCanRunLocalLauncherWithoutPath(String format) throws Exception {
        Path home = unpack(format);
        Path bin = home.resolve("bin");
        Result version = process(List.of("/bin/sh", "faultora", "--version"), bin, true);
        assertThat(version.code()).as(version.output()).isZero();
        assertThat(version.output()).contains("faultora " + System.getProperty("faultora.version"));
        Result validated = process(List.of("/bin/sh", "faultora", "validate",
                "--scenario", "../examples/payment-service/scenarios/passing.yaml",
                "--openapi", "../examples/payment-service/openapi.yaml"), bin, true);
        assertThat(validated.code()).as(validated.output()).isZero();
        Result rejected = process(List.of("/bin/sh", "faultora", "validate"), bin, true);
        assertThat(rejected.code()).as(rejected.output()).isEqualTo(2);

        Result missing = process(List.of("/bin/sh", "-c", Files.readString(bin.resolve("faultora")),
                "missing-faultora-launcher"), bin, true);
        assertThat(missing.code()).as(missing.output()).isEqualTo(127);
        assertThat(missing.output()).contains("faultora: cannot locate launcher script");
    }

    private Path unpack(String format) throws Exception {
        String name = "faultora-" + System.getProperty("faultora.version");
        Path archive = Path.of(System.getProperty("faultora.cliJar"))
                .toAbsolutePath().resolveSibling(name + "." + format);
        assertThat(archive).as("CLI archive built earlier in the reactor").isRegularFile();
        Path destination = Files.createDirectory(directory.resolve("installation with spaces " + PATH_LABEL));
        if (format.equals("zip")) {
            try (InputStream input = Files.newInputStream(archive);
                    ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path output = archiveEntry(destination, name, entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        Files.copy(zip, output);
                    }
                }
            }
            if (!windows()) {
                // ZIP extractors do not all retain the POSIX mode. The guide
                // documents chmod for this case; tar.gz must preserve it itself.
                assertThat(destination.resolve(name).resolve("bin/faultora").toFile().setExecutable(true))
                        .as("make the ZIP launcher executable").isTrue();
            }
        } else {
            // Inspect the generated tar before extracting, including its types:
            // no path escapes, links or special files are part of this package.
            Result names = process(List.of("tar", "-tzf", archive.toString()), destination, null);
            assertThat(names.code()).as(names.output()).isZero();
            for (String entry : names.output().lines().filter(line -> !line.isBlank()).toList()) {
                archiveEntry(destination, name, entry);
            }
            Result types = process(List.of("tar", "-tvzf", archive.toString()), destination, null);
            assertThat(types.code()).as(types.output()).isZero();
            assertThat(types.output().lines().filter(line -> !line.isBlank()))
                    .allMatch(line -> line.startsWith("-") || line.startsWith("d"));
            Result extracted = process(List.of("tar", "-xzf", archive.toString(), "-C", destination.toString()),
                    directory, null);
            assertThat(extracted.code()).as(extracted.output()).isZero();
        }
        Path home = destination.resolve(name);
        assertThat(home).isDirectory();
        if (!windows()) {
            assertThat(Files.isExecutable(home.resolve("bin/faultora")))
                    .as("the Linux launcher is executable after extraction").isTrue();
        }
        return home;
    }

    private static Path archiveEntry(Path destination, String root, String entry) {
        assertThat(entry).doesNotContain("\\", "../").doesNotStartWith("/");
        assertThat(entry).startsWith(root + "/");
        Path output = destination.resolve(entry).normalize();
        assertThat(output.startsWith(destination)).as("archive path stays inside destination").isTrue();
        return output;
    }

    private void checkContents(Path home) throws Exception {
        String version = System.getProperty("faultora.version");
        assertThat(home.resolve("LICENSE")).content().contains("Apache License", "Version 2.0");
        assertThat(home.resolve("NOTICE")).isRegularFile();
        assertThat(home.resolve("THIRD-PARTY.txt")).isRegularFile();
        assertThat(home.resolve("README.md")).isRegularFile();
        assertThat(home.resolve("docs/INSTALLATION.md")).isRegularFile();
        checkLocalLinks(home);
        assertThat(Files.readString(home.resolve("bin/faultora")))
                .startsWith("#!/bin/sh\n").contains("faultora-" + version + ".jar").doesNotContain("\r");
        assertThat(Files.readString(home.resolve("bin/faultora.cmd")))
                .startsWith("@echo off\r\n").contains("faultora-" + version + ".jar");
        try (Stream<Path> files = Files.walk(home)) {
            assertThat(files.filter(Files::isRegularFile).map(home::relativize)
                    .map(path -> path.toString().replace('\\', '/')))
                    .noneMatch(path -> path.endsWith(".java") || path.endsWith(".class")
                            || path.endsWith("pom.xml") || path.startsWith("target/")
                            || path.contains("/target/") || path.startsWith(".git/"));
        }
        try (Stream<Path> jars = Files.list(home.resolve("lib"))) {
            assertThat(jars.map(path -> path.getFileName().toString()))
                    .containsExactly("faultora-" + version + ".jar");
        }
        Path jarPath = home.resolve("lib/faultora-" + version + ".jar");
        assertThat(Files.mismatch(jarPath, Path.of(System.getProperty("faultora.cliJar")))).isEqualTo(-1);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Implementation-Version"))
                    .isEqualTo(version);
            assertThat(jar.stream().map(ZipEntry::getName))
                    .noneMatch(name -> name.endsWith(".java") || name.startsWith("examples/")
                            || name.startsWith("docs/") || name.startsWith("dev/faultora/examples/"));
        }
    }

    private void checkCommands(Path home, boolean javaHome) throws Exception {
        checkCommands(home, javaHome, home.resolve(windows() ? "bin/faultora.cmd" : "bin/faultora"));
    }

    private void checkCommands(Path home, boolean javaHome, Path launcher) throws Exception {
        Path work = Files.createTempDirectory(directory, "caller with spaces " + PATH_LABEL);
        Path scenario = work.resolve("scenario with spaces " + PATH_LABEL + ".yaml");
        Files.copy(home.resolve("examples/payment-service/scenarios/passing.yaml"), scenario);
        Path openapi = home.resolve("examples/payment-service/openapi.yaml");
        List<String> command = new ArrayList<>();
        if (windows()) {
            command.addAll(List.of("cmd.exe", "/d", "/c", "call", launcher.toString()));
        } else {
            command.add(launcher.toString());
        }
        Result version = launch(command, work, javaHome, "--version");
        assertThat(version.code()).as(version.output()).isZero();
        assertThat(version.output()).contains("faultora " + System.getProperty("faultora.version"));
        Result validated = launch(command, work, javaHome, "validate", "--scenario", scenario.getFileName().toString(),
                "--openapi", openapi.toString());
        assertThat(validated.code()).as(validated.output()).isZero();
        Result rejected = launch(command, work, javaHome, "validate");
        assertThat(rejected.code()).as(rejected.output()).isEqualTo(2);
    }

    private static void checkLocalLinks(Path home) throws IOException {
        Pattern link = Pattern.compile("\\]\\(([^\\s)]+)\\)");
        try (Stream<Path> files = Files.walk(home)) {
            for (Path guide : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                Matcher matcher = link.matcher(Files.readString(guide));
                while (matcher.find()) {
                    String target = matcher.group(1);
                    if (target.startsWith("#") || target.startsWith("//")
                            || target.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                        continue;
                    }
                    String path = target.split("[#?]", 2)[0];
                    Path resolved = guide.getParent().resolve(path).normalize();
                    assertThat(resolved.startsWith(home)).as("local link stays in archive: %s", target).isTrue();
                    assertThat(resolved).as("local link %s in %s", target, home.relativize(guide)).exists();
                }
            }
        }
    }

    private Result launch(List<String> prefix, Path work, boolean javaHome, String... args) throws Exception {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of(args));
        return process(command, work, javaHome);
    }

    private Result process(List<String> command, Path work, Boolean javaHome) throws Exception {
        Path log = Files.createTempFile(directory, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        if (javaHome != null) {
            if (javaHome) {
                builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
                // Ensure a launcher ignoring JAVA_HOME cannot pass by using
                // the Java that happened to be on the caller's PATH.
                String pathKey = builder.environment().keySet().stream()
                        .filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
                builder.environment().put(pathKey, "");
            } else {
                builder.environment().remove("JAVA_HOME");
                String pathKey = builder.environment().keySet().stream()
                        .filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
                String path = builder.environment().getOrDefault(pathKey, "");
                builder.environment().put(pathKey, Path.of(System.getProperty("java.home"), "bin")
                        + java.io.File.pathSeparator + path);
            }
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("archive command finished: %s", command).isTrue();
            return new Result(process.exitValue(), new String(Files.readAllBytes(log),
                    Charset.forName(System.getProperty("native.encoding", "UTF-8"))));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Archive command did not stop");
                }
            }
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String nativePathLabel() {
        // Windows Java launchers encode argv using the native code page,
        // independently of file.encoding. Keep this fixture representable.
        return Stream.of("пример", "résumé", "files")
                .filter(Charset.forName(System.getProperty("native.encoding", "UTF-8"))
                        .newEncoder()::canEncode).findFirst().orElseThrow();
    }

    private record Result(int code, String output) { }
}
