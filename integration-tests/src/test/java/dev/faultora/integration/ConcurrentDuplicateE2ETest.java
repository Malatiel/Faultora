package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flagship reliability scenario end to end: two concurrent create-payment
 * requests with one Idempotency-Key under injected latency.
 * <p>
 * The suite must pass against the correct (atomic) implementation and detect
 * the deliberately broken check-then-act variant — the M2 acceptance
 * criterion that the same scenario finds a known defect.
 */
class ConcurrentDuplicateE2ETest {

    private PaymentApi api;

    @AfterEach
    void stopServer() {
        if (api != null) api.stop();
    }

    private int runScenario(String outputDirName) throws IOException {
        Path scenario = Path.of("src/test/resources/scenarios/fault-concurrent-duplicate.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), outputDirName);
        Files.createDirectories(outputDir);

        FaultoraCli cli = new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        return cli.run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json",
                "--output", outputDir.toString()
        });
    }

    @Test
    void atomicIdempotencyHoldsTheInvariantUnderConcurrentRequests() throws IOException {
        api = new PaymentApi(true);
        api.start();

        int exit = runScenario("faultora-e2e-race-fixed");

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void brokenIdempotencyIsDetectedByTheSameScenario() throws IOException {
        api = new PaymentApi(false);
        api.start();

        int exit = runScenario("faultora-e2e-race-broken");

        // The check-then-act race creates two payments; the invariant
        // assertion must fail and surface as a test failure.
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
    }

    @Test
    void idempotencyKeyCanBeOverriddenFromTheCommandLine() throws IOException {
        api = new PaymentApi(true);
        api.start();

        Path scenario = Path.of("src/test/resources/scenarios/fault-concurrent-duplicate.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-race-input");
        Files.createDirectories(outputDir);

        FaultoraCli cli = new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        int exit = cli.run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--input", "idempotency-key=cli-supplied-key",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }
}
