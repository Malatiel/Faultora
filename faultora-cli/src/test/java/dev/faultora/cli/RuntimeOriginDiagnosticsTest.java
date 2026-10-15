package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOriginDiagnosticsTest {
    @TempDir Path directory;

    enum Origin { NON_FILE, ABSENT, UNREADABLE }

    @ParameterizedTest
    @EnumSource(Origin.class)
    void unsupportedRuntimeOriginProducesJsonFailureOnRepeatedCalls(Origin origin) throws Exception {
        URL location = switch (origin) {
            case NON_FILE -> URI.create("https://synthetic.invalid/runtime.jar").toURL();
            case ABSENT -> null;
            case UNREADABLE -> directory.resolve("missing-runtime.jar").toUri().toURL();
        };
        Path scenario = directory.resolve("scenario.yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: synthetic}
                execute:
                  - id: pause
                    type: wait
                    timeout: 1ms
                """);
        Class<?> cli = Class.forName(FaultoraCli.class.getName(), true, loader(location));
        for (int attempt = 0; attempt < 2; attempt++) {
            StringWriter stdout = new StringWriter();
            StringWriter stderr = new StringWriter();
            Object instance = cli.getConstructor(PrintWriter.class, PrintWriter.class)
                    .newInstance(new PrintWriter(stdout, true), new PrintWriter(stderr, true));
            Object exit = cli.getMethod("run", String[].class).invoke(instance, (Object) new String[]{
                    "--diagnostics=json", "test", "--scenario", scenario.toString(), "--preview"});
            assertThat(exit).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
            var json = new ObjectMapper().readTree(stdout.toString());
            assertThat(json.path("exitCode").asInt()).isEqualTo(3);
            assertThat(json.path("diagnostics").get(0).path("code").asText()).isEqualTo("COMMAND_FAILED");
            assertThat(stderr.toString()).isEmpty();

            stdout.getBuffer().setLength(0);
            stderr.getBuffer().setLength(0);
            Object plainExit = cli.getMethod("run", String[].class).invoke(instance, (Object) new String[]{
                    "test", "--scenario", scenario.toString(), "--preview"});
            assertThat(plainExit).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
            assertThat(stdout.toString()).isEmpty();
            assertThat(stderr.toString()).contains("Cannot verify the runtime artifact's")
                    .doesNotContain("ExceptionInInitializerError", "NoClassDefFoundError", "synthetic.invalid");
        }
    }

    private static ClassLoader loader(URL location) {
        return new SecureClassLoader(RuntimeOriginDiagnosticsTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("dev.faultora.cli.") && !name.startsWith("dev.faultora.runtime.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = input.readAllBytes();
                            CodeSource source = name.equals("dev.faultora.runtime.ExtensionRegistry")
                                    || name.startsWith("dev.faultora.runtime.ExtensionRegistry$")
                                    ? (location == null ? null : new CodeSource(location, (Certificate[]) null))
                                    : FaultoraCli.class.getProtectionDomain().getCodeSource();
                            type = defineClass(name, bytes, 0, bytes.length, source);
                        } catch (IOException unreadable) {
                            throw new ClassNotFoundException(name, unreadable);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
    }
}
