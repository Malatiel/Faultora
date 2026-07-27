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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for request values generated from the operation's schema,
 * run by the packaged CLI against the example payment service.
 */
class GeneratedRequestE2ETest {

    private static final Pattern DIGEST = Pattern.compile("\"digest\":\"(sha256:[0-9a-f]+)\"");

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

    private int run(Path scenario, Path outputDir, String seed) throws IOException {
        Files.createDirectories(outputDir);
        return new FaultoraCli(new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .run(new String[]{
                        "test",
                        "--scenario", scenario.toString(),
                        "--openapi", ExampleFixtures.openApi().toString(),
                        "--target", api.baseUrl(),
                        "--allow-private",
                        "--seed", seed,
                        "--format", "console,json",
                        "--output", outputDir.toString()
                });
    }

    @Test
    void aGeneratedPayloadIsAcceptedAndTheExplicitFieldSurvives() throws IOException {
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-generated");

        int exit = run(ExampleFixtures.scenario("generated-payment.yaml"), outputDir, "12345");

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("INPUTS_GENERATED");
        assertThat(events).contains("\"strategy\":\"valid\"");
    }

    @Test
    void theSameSeedReplaysTheSameGeneratedRequest() throws IOException {
        Path scenario = ExampleFixtures.scenario("generated-payment.yaml");
        Path first = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-generated-1");
        Path second = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-generated-2");
        Path different = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-generated-3");

        assertThat(run(scenario, first, "4242")).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(run(scenario, second, "4242")).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(run(scenario, different, "9999")).isEqualTo(FaultoraCli.EXIT_PASS);

        // The digest recorded for the generated body is what a replay is
        // checked against: same seed, same request.
        assertThat(digestsIn(first)).isEqualTo(digestsIn(second));
        assertThat(digestsIn(first)).isNotEqualTo(digestsIn(different));
    }

    @Test
    void aSchemaThatCannotBeGeneratedFailsBeforeAnyRequest() throws IOException {
        Path openApi = Files.createTempFile("faultora-pattern-openapi", ".yaml");
        Files.writeString(openApi, """
                openapi: "3.0.3"
                info:
                  title: Pattern API
                  version: "1.0.0"
                paths:
                  /payments:
                    post:
                      operationId: create-payment
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [iban]
                              properties:
                                iban:
                                  type: string
                                  pattern: "^[A-Z]{2}[0-9]{20}$"
                      responses:
                        "201":
                          description: Created
                """);
        Path scenario = Files.createTempFile("faultora-pattern-scenario", ".yaml");
        Files.writeString(scenario, """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: unsatisfiable-generation
                execute:
                  - id: create-payment
                    type: operation
                    operationId: create-payment
                    generate:
                      fields: [body]
                """);

        int exit = new FaultoraCli(
                new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .run(new String[]{
                        "test",
                        "--scenario", scenario.toAbsolutePath().toString(),
                        "--openapi", openApi.toAbsolutePath().toString(),
                        "--target", api.baseUrl(),
                        "--allow-private",
                        "--output", Path.of(System.getProperty("java.io.tmpdir"),
                                "faultora-e2e-generated-invalid").toString()
                });

        // Nothing is sent: the contract already says the generator cannot
        // satisfy it, and the diagnostic names the field.
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    /**
     * Digests of generated requests only. Response evidence is digested too,
     * and those digests legitimately differ between runs.
     */
    private List<String> digestsIn(Path outputDir) throws IOException {
        List<String> digests = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(outputDir.resolve("events.ndjson"))) {
            if (!line.contains("INPUTS_GENERATED")) {
                continue;
            }
            Matcher matcher = DIGEST.matcher(line);
            if (matcher.find()) {
                digests.add(matcher.group(1));
            }
        }
        assertThat(digests).describedAs("generated-input digests in %s", outputDir).isNotEmpty();
        return digests;
    }
}
