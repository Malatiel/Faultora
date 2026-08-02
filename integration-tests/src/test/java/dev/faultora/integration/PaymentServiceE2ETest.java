package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
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
        Path scenario = ExampleFixtures.scenario("passing.yaml");

        int exit = createCli().run(new String[]{
                "validate",
                "--scenario", scenario.toAbsolutePath().toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void discoverCommandListsOperations() {
        Path openApi = ExampleFixtures.openApi();

        int exit = createCli().run(new String[]{
                "discover",
                "--from-openapi", openApi.toAbsolutePath().toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void initCommandGeneratesScenario() throws IOException {
        Path openApi = ExampleFixtures.openApi();
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
    void passingScenarioRunsSuccessfully() throws IOException {
        Path scenario = ExampleFixtures.scenario("passing.yaml");
        Path openApi = ExampleFixtures.openApi();
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-pass");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("events.ndjson"), "{\"stale\":true}\n");

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json,junit,html",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(outputDir.resolve("events.ndjson")).exists();
        assertThat(outputDir.resolve("report.json")).exists();
        assertThat(outputDir.resolve("report.xml")).exists();
        assertThat(outputDir.resolve("report.html")).exists();
        assertThat(Files.readString(outputDir.resolve("events.ndjson")))
                .doesNotContain("\"stale\":true");
    }

    @Test
    void aStepNamingSomethingUnboundFailsTheRunRatherThanTheRunner()
            throws IOException {
        // The scenario is written here rather than shipped as an example: it
        // is about a defect, and an example is something to copy. What it
        // proves is the whole path — a template naming nothing, an operation
        // step refusing to be invoked with it, and the exit code a reader acts
        // on. A typo in a scenario is a test failure, not a runner failure.
        Path outputDir = Files.createTempDirectory("faultora-e2e-unbound-");
        Path scenario = outputDir.resolve("names-a-ghost.yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: names-a-ghost
                execute:
                  - id: create-payment
                    type: operation
                    operationId: create-payment
                    outputAs: created
                    inputs:
                      body:
                        amount: 2500
                        currency: EUR
                  - id: read-back
                    type: operation
                    operationId: get-payment
                    dependsOn: [create-payment]
                    inputs:
                      paymentId: "{{steps.created.body.reference}}"
                """);

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toString(),
                "--openapi", ExampleFixtures.openApi().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        String journal = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(journal).contains("INPUTS_UNRESOLVABLE");
        assertThat(journal)
                .as("the message names the input the template sits in")
                .contains("paymentId");
    }

    @Test
    void failingScenarioReturnsNonZero() {
        Path scenario = ExampleFixtures.scenario("failing.yaml");
        Path openApi = ExampleFixtures.openApi();

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", openApi.toAbsolutePath().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console",
                "--output", Path.of(System.getProperty("java.io.tmpdir"),
                        "faultora-e2e-fail").toString()
        });

        // Failing scenario should return non-zero
        assertThat(exit).isNotEqualTo(FaultoraCli.EXIT_PASS);
    }
}
