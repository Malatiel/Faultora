package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end fault-injection tests: the packaged CLI runs fault scenarios
 * against a fresh payment service and produces fault-aware reports.
 * <p>
 * Each test starts its own service instance because the duplicate-payment
 * scenario asserts on the total number of payments in the store.
 */
class FaultInjectionE2ETest {

    private PaymentApi api;

    @BeforeEach
    void startServer() throws IOException {
        api = new PaymentApi();
        api.start();
    }

    @AfterEach
    void stopServer() {
        if (api != null) api.stop();
    }

    private FaultoraCli createCli() {
        return new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
    }

    @Test
    void latencyFaultScenarioPassesAndReportsTheFaultWindow() throws IOException {
        Path scenario = Path.of("src/test/resources/scenarios/fault-latency.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-fault-latency");
        Files.createDirectories(outputDir);

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json,html",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);

        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("FAULT_INJECTED");
        assertThat(events).contains("FAULT_ROLLED_BACK");
        assertThat(events).contains("http-latency");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertThat(html).contains("<h2>Faults</h2>");
        assertThat(html).contains("http-latency");
    }

    @Test
    void duplicatePaymentIsPreventedUnderResponseLoss() throws IOException {
        Path scenario = Path.of("src/test/resources/scenarios/fault-duplicate-payment.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-fault-dup");
        Files.createDirectories(outputDir);

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);

        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("http-response-loss");
        assertThat(events).contains("FAULT_ROLLED_BACK");
    }

    @Test
    void retryScenarioRecoversOnceTheOutageClears() throws IOException {
        Path scenario = Path.of("src/test/resources/scenarios/fault-retry.yaml");
        Path openApi = Path.of("src/test/resources/openapi.yaml");
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-fault-retry");
        Files.createDirectories(outputDir);

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);

        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("OPERATION_RETRIED");
        assertThat(events).contains("FAULT_INJECTED_ERROR");
    }

    @Test
    void faultScenarioValidatesWithoutContactingTheTarget() {
        Path scenario = Path.of("src/test/resources/scenarios/fault-duplicate-payment.yaml");

        int exit = createCli().run(new String[]{
                "validate",
                "--scenario", scenario.toAbsolutePath().toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }
}
