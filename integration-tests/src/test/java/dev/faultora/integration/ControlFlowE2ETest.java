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
 * End-to-end tests for the control-flow blocks: repeat groups and
 * eventually (poll-until) groups, run by the packaged CLI against a fresh
 * payment service.
 * <p>
 * Each test starts its own service instance because the batch scenario
 * asserts on the total number of payments in the store.
 */
class ControlFlowE2ETest {

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

    private int run(String scenarioName, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        return createCli().run(new String[]{
                "test",
                "--scenario", ExampleFixtures.scenario(scenarioName).toString(),
                "--openapi", ExampleFixtures.openApi().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json,junit,html",
                "--output", outputDir.toString()
        });
    }

    @Test
    void eventuallyBlockConvergesOnAsynchronousSettlement() throws IOException {
        Path outputDir = Path.of(
                System.getProperty("java.io.tmpdir"), "faultora-e2e-eventually");

        int exit = run("eventually-settlement.yaml", outputDir);

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);

        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("CONDITION_POLLED");
        // The payment is not settled on the first read, so the block polls
        // more than once before its conditions hold.
        assertThat(events.split("CONDITION_POLLED", -1).length - 1).isGreaterThan(1);
        assertThat(events).contains("\"satisfied\":true");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertThat(html).contains("settlement-visible");
        assertThat(html).contains("poll");
    }

    @Test
    void eventuallyBlockFailsWhenTheConditionNeverHolds() throws IOException {
        Path scenario = Files.createTempFile("faultora-eventually-timeout", ".yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: eventually-never-holds
                execute:
                  - id: create-payment
                    type: operation
                    operationId: create-payment
                    outputAs: created
                    inputs:
                      body:
                        amount: 100
                        currency: EUR
                  - id: never-refunded
                    type: eventually
                    timeout: 600ms
                    interval: 100ms
                    dependsOn: [create-payment]
                    steps:
                      - id: poll-payment
                        type: operation
                        operationId: get-payment
                        inputs:
                          paymentId: "{{steps.created.body.id}}"
                    until:
                      - assertionType: jsonpath
                        params:
                          path: status
                          equals: refunded
                """);
        Path outputDir = Path.of(
                System.getProperty("java.io.tmpdir"), "faultora-e2e-eventually-timeout");
        Files.createDirectories(outputDir);

        int exit = createCli().run(new String[]{
                "test",
                "--scenario", scenario.toAbsolutePath().toString(),
                "--openapi", ExampleFixtures.openApi().toString(),
                "--target", api.baseUrl(),
                "--allow-private",
                "--format", "console,json",
                "--output", outputDir.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("EVENTUALLY_TIMEOUT");
    }

    @Test
    void repeatBlockRunsOncePerItem() throws IOException {
        Path outputDir = Path.of(
                System.getProperty("java.io.tmpdir"), "faultora-e2e-repeat");

        int exit = run("repeat-batch.yaml", outputDir);

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);

        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("create-payment:0");
        assertThat(events).contains("create-payment:1");
        assertThat(events).contains("create-payment:2");

        String junit = Files.readString(outputDir.resolve("report.xml"));
        assertThat(junit).contains("create-batch");
    }

    @Test
    void controlFlowScenariosValidateWithoutContactingTheTarget() {
        for (String scenarioName : new String[]{
                "eventually-settlement.yaml", "repeat-batch.yaml"}) {
            int exit = createCli().run(new String[]{
                    "validate",
                    "--scenario", ExampleFixtures.scenario(scenarioName).toString()
            });
            assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        }
    }
}
