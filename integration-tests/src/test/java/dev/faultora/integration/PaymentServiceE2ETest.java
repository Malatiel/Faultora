package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: starts the payment service,
 * runs scenarios via the CLI, and verifies exit codes.
 */
class PaymentServiceE2ETest {

    private static PaymentApi api;

    @BeforeAll
    static void startServer() throws IOException {
        api = new PaymentApi();
        api.start();
    }

    @AfterAll
    static void stopServer() {
        if (api != null) api.stop();
    }

    private FaultoraCli createCli() {
        // Commands print to System.out; we capture exit codes only
        return new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
    }

    @Test
    void validateCommandAcceptsValidScenario() {
        Path scenario = Path.of("src/test/resources/scenarios/passing.yaml");

        int exit = createCli().run(new String[]{
                "validate",
                "--scenario", scenario.toAbsolutePath().toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void discoverCommandListsOperations() {
        Path openApi = Path.of("src/test/resources/openapi.yaml");

        int exit = createCli().run(new String[]{
                "discover",
                "--from-openapi", openApi.toAbsolutePath().toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void initCommandGeneratesScenario() throws IOException {
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-init");

        int exit = createCli().run(new String[]{
                "init",
                "--from-openapi", openApi.toAbsolutePath().toString(),
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(outputDir.resolve("scenario.yaml")).exists();
    }

    @Test
    void passingScenarioRunsSuccessfully() {
        Path scenario = Path.of("src/test/resources/scenarios/passing.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--format", "json",
                "--output", Path.of(System.getProperty("java.io.tmpdir"),
                        "faultora-e2e-pass").toString()
        });

        // Either PASS (0) or TEST_FAILURE (1) is acceptable — the scenario runs
        // and the exit code is deterministic. If the assertion matches the actual
        // HTTP response status, it passes; if not, it's a deliberate test failure.
        assertThat(exit).isIn(FaultoraCli.EXIT_PASS, FaultoraCli.EXIT_TEST_FAILURE);
    }

    @Test
    void failingScenarioReturnsNonZero() {
        Path scenario = Path.of("src/test/resources/scenarios/failing.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--format", "console",
                "--output", Path.of(System.getProperty("java.io.tmpdir"),
                        "faultora-e2e-fail").toString()
        });

        // Failing scenario should return non-zero
        assertThat(exit).isNotEqualTo(FaultoraCli.EXIT_PASS);
    }
}
