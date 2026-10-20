package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * validate refuses a catalog that test would refuse, before anything runs.
 * <p>
 * It used to read only the scenario, so a catalog of an unknown version passed
 * validate and failed the first real run — the opposite of what a check before
 * a run is for.
 */
class ValidateCommandTest {
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: validated}
            execute:
              - id: pause
                type: wait
                timeout: 1ms
            """;

    @TempDir Path directory;

    private record Invocation(int exit, String stdout, String stderr) { }

    private Invocation run(String... args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(args);
        return new Invocation(exit, stdout.toString(), stderr.toString());
    }

    private Path write(String name, String content) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static String observations(String apiVersion) {
        return """
                apiVersion: %s
                kind: Observations
                servers:
                  ledger: { url: "jdbc:h2:mem:validate" }
                observations:
                  entries: { server: ledger, sql: SELECT 1 }
                """.formatted(apiVersion);
    }

    @Test
    void aCatalogOfAnUnknownVersionFailsValidateWithTheExitCodeTestWouldGive() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);
        Path catalog = write("observations.yaml", observations("faultora.dev/v99"));

        var validated = run("validate", "--scenario", scenario.toString(), "--observations", catalog.toString());
        var tested = run("test", "--preview", "--scenario", scenario.toString(),
                "--observations", catalog.toString());

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG).isEqualTo(tested.exit());
        assertThat(validated.stdout()).contains("Scenario is VALID");
        assertThat(validated.stderr()).contains("Catalog is INVALID", "faultora.dev/v99");
    }

    @Test
    void theMachineDiagnosticIsTheOneTestReports() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);
        Path catalog = write("observations.yaml", observations("faultora.dev/v99"));

        var validated = run("--diagnostics=json", "validate", "--scenario", scenario.toString(),
                "--observations", catalog.toString());

        JsonNode result = new ObjectMapper().readTree(validated.stdout());
        assertThat(result.path("exitCode").asInt()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
        assertThat(result.path("diagnostics")).anySatisfy(diagnostic ->
                assertThat(diagnostic.path("code").asText()).isEqualTo("CATALOG_IMPORT_FAILED"));
    }

    @Test
    void aValidCatalogIsReportedWithWhatItDeclaresAndItsWarnings() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);
        Path catalog = write("observations.yaml", observations("faultora.dev/v1alpha1"));

        var validated = run("validate", "--scenario", scenario.toString(), "--observations", catalog.toString());

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(validated.stdout()).contains("Catalog is VALID: 1 operations from observations");
        assertThat(validated.stderr()).contains("WARNING: observations catalog:", "deprecated");
    }

    @Test
    void twoDocumentsClaimingOneNameAreRefusedAsTestRefusesThem() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);
        Path catalog = write("observations.yaml", observations("faultora.dev/v1"));
        Path openApi = write("openapi.yaml", """
                openapi: 3.0.3
                info: { title: Ledger, version: 1.0.0 }
                servers: [{ url: "http://api.example.com" }]
                paths:
                  /entries:
                    get: { operationId: entries, responses: { '200': { description: ok } } }
                """);

        var validated = run("validate", "--scenario", scenario.toString(),
                "--openapi", openApi.toString(), "--observations", catalog.toString());

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
        assertThat(validated.stderr()).contains("Catalog is INVALID", "'entries'");
    }

    @Test
    void withoutCatalogsValidateSaysWhatItAlwaysSaid() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);

        var validated = run("validate", "--scenario", scenario.toString());

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(validated.stdout()).contains("Scenario is VALID").doesNotContain("Catalog");
        assertThat(validated.stderr()).isEmpty();
    }

    @Test
    void anUnreadableCatalogIsAReadFailureNotAValidScenario() throws Exception {
        Path scenario = write("scenario.yaml", SCENARIO);

        var validated = run("validate", "--scenario", scenario.toString(),
                "--asyncapi", directory.resolve("missing.yaml").toString());

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
        assertThat(validated.stderr()).contains("Cannot read catalog file");
    }
}
