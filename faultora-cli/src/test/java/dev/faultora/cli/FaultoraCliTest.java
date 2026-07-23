package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FaultoraCliTest {

    private FaultoraCli createCli() {
        return new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
    }

    @Test
    void helpReturnsZero() {
        int exit = createCli().run(new String[]{"--help"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void versionReturnsZero() {
        int exit = createCli().run(new String[]{"--version"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void noArgsReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void unknownCommandReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{"bogus"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void validateMissingScenarioReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{"validate"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void validateValidScenarioReturnsZero(@TempDir Path temp) throws IOException {
        Path scenario = temp.resolve("test.yaml");
        Files.writeString(scenario, VALID_SCENARIO);

        int exit = createCli().run(new String[]{"validate", "--scenario", scenario.toString()});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void validateInvalidScenarioReturnsInvalidConfig(@TempDir Path temp) throws IOException {
        Path scenario = temp.resolve("bad.yaml");
        Files.writeString(scenario, "not: a valid scenario");

        int exit = createCli().run(new String[]{"validate", "--scenario", scenario.toString()});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void discoverMissingOpenApiReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{"discover"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void initMissingOpenApiReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{"init"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void testMissingScenarioReturnsInvalidConfig() {
        int exit = createCli().run(new String[]{"test"});
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void testRejectsInvalidSeed(@TempDir Path temp) throws IOException {
        Path scenario = temp.resolve("test.yaml");
        Files.writeString(scenario, VALID_SCENARIO);

        int exit = createCli().run(new String[]{
                "test", "--scenario", scenario.toString(), "--seed", "not-a-number"
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void testRejectsUnknownReportFormat(@TempDir Path temp) throws IOException {
        Path scenario = temp.resolve("test.yaml");
        Files.writeString(scenario, VALID_SCENARIO);

        int exit = createCli().run(new String[]{
                "test", "--scenario", scenario.toString(), "--format", "console,unknown"
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void testRunsStructuralValidation(@TempDir Path temp) throws IOException {
        Path scenario = temp.resolve("unsupported.yaml");
        Files.writeString(scenario, VALID_SCENARIO.replace(
                "type: operation", "type: parallel"));

        int exit = createCli().run(new String[]{
                "test", "--scenario", scenario.toString()
        });

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
    }

    private static final String VALID_SCENARIO = """
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario
            metadata:
              name: test-scenario
              description: A test scenario
            execute:
              - id: step-1
                type: operation
                operationId: create-payment
            assertions:
              - id: check-status
                assertionType: status
                targetStep: step-1
                params:
                  expected: 200
            cleanup:
              []
            """;
}
