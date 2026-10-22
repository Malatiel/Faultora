package dev.faultora.integration;

import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first half hour of a new user, through the executable they download.
 * <p>
 * init, validate, test and the reports, in that order, against the example
 * API and its published description — with nothing edited in between. The
 * generated scenario used to be refused by the very next command, so this is
 * the path that has to keep working, not just each command on its own.
 */
class NewUserPathE2ETest {

    @TempDir Path directory;

    private record Ran(int exit, String stdout, String stderr) { }

    private Ran faultora(String... args) throws Exception {
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        assertThat(jar).as("packaged CLI built earlier in the reactor").isRegularFile();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", jar.toString()));
        command.addAll(List.of(args));
        Path stdout = Files.createTempFile(directory, "stdout", ".txt");
        Path stderr = Files.createTempFile(directory, "stderr", ".txt");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("faultora %s finished", args[0]).isTrue();
            return new Ran(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void theCommandInitPrintsRunsWhenPastedIntoARealShell() throws Exception {
        // Double-quoted, "catalog $NAME.yaml" was expanded by PowerShell and sh
        // into a different file. The variable is set here so expansion would
        // show; the command must still find the file it names.
        Path catalog = directory.resolve("catalog $FAULTORA_E07_LITERAL it's.yaml");
        Files.copy(ExampleFixtures.openApi(), catalog);
        Ran init = faultora("init", "--from-openapi", catalog.toString(),
                "--output", "starter $FAULTORA_E07_LITERAL");
        assertThat(init.exit()).as(init.stderr()).isZero();
        String validate = init.stdout().lines().map(String::strip)
                .filter(line -> line.startsWith("faultora validate ")).findFirst().orElseThrow();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath().toString();
        List<String> shell = windows()
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "& " + powerShellQuoted(java) + " -jar " + powerShellQuoted(jar)
                                + validate.substring("faultora".length()) + "; exit $LASTEXITCODE")
                : List.of("/bin/sh", "-c",
                        shQuoted(java) + " -jar " + shQuoted(jar) + validate.substring("faultora".length()));
        ProcessBuilder pasted = new ProcessBuilder(shell).directory(directory.toFile()).redirectErrorStream(true);
        pasted.environment().put("FAULTORA_E07_LITERAL", "expanded");
        Process process = pasted.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(validate + "\n" + output).isZero();
        assertThat(output).contains("Scenario is VALID", "Catalog is VALID");
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String powerShellQuoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static String shQuoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    @Test
    void initThenValidateThenTestThenReportsWorksAsGenerated() throws Exception {
        String openApi = ExampleFixtures.openApi().toAbsolutePath().toString();
        PaymentApi api = new PaymentApi();
        api.start();
        try {
            Ran init = faultora("init", "--from-openapi", openApi, "--output", "starter");
            assertThat(init.exit()).as(init.stderr()).isZero();
            assertThat(init.stdout()).contains("Next:");
            String scenario = directory.resolve("starter/scenario.yaml").toString();

            Ran validated = faultora("validate", "--scenario", scenario, "--openapi", openApi);
            assertThat(validated.exit()).as(validated.stderr()).isZero();
            assertThat(validated.stdout()).contains("Scenario is VALID", "Catalog is VALID");

            Ran previewed = faultora("test", "--preview", "--scenario", scenario, "--openapi", openApi,
                    "--target", api.baseUrl());
            assertThat(previewed.exit()).as(previewed.stderr()).isZero();

            Ran withoutPermission = faultora("test", "--scenario", scenario, "--openapi", openApi,
                    "--target", api.baseUrl(), "--output", "refused");
            assertThat(withoutPermission.exit()).isEqualTo(1);
            assertThat(withoutPermission.stderr()).contains("--allow-private");

            Ran tested = faultora("test", "--scenario", scenario, "--openapi", openApi,
                    "--target", api.baseUrl(), "--allow-private",
                    "--format", "console,json,html", "--output", "results");
            assertThat(tested.exit()).as(tested.stdout() + tested.stderr()).isZero();
            assertThat(tested.stdout()).contains("Result: PASSED");
            assertThat(directory.resolve("results/report.json")).isRegularFile();
            assertThat(directory.resolve("results/report.html")).isRegularFile();
            assertThat(Files.readString(directory.resolve("results/events.ndjson")))
                    .contains("\"eventType\":\"ASSERTION_EVALUATED\"", "\"outcome\":\"PASS\"");
        } finally {
            api.stop();
        }
    }
}
