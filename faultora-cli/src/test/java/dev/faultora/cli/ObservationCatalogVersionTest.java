package dev.faultora.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An observation catalog declaring a version this release does not read is
 * refused while the catalog is assembled, before any database is contacted.
 */
class ObservationCatalogVersionTest {

    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: catalog-version}
            execute:
              - id: pause
                type: wait
                timeout: 1ms
            """;

    private static final String CATALOG = """
            apiVersion: %s
            kind: Observations
            servers:
              ledger:
                url: jdbc:synthetic-unreachable://127.0.0.1:1/never
            observations:
              observe:
                server: ledger
                sql: SELECT 1
            """;

    @TempDir Path directory;

    @Test
    void anUnknownCatalogVersionStopsTheRunBeforeExecution() throws Exception {
        Invocation result = run("faultora.dev/v99");

        assertThat(result.exit()).isNotZero();
        assertThat(result.stdout() + result.stderr())
                .contains("Unsupported observation catalog apiVersion", "faultora.dev/v99")
                .doesNotContain("Running scenario", "Result: PASSED");
        assertThat(directory.resolve("reports")).doesNotExist();
    }

    @Test
    void theCurrentCatalogVersionIsAccepted() throws Exception {
        Invocation result = run("faultora.dev/v1");

        assertThat(result.exit()).as(result.stdout() + result.stderr()).isZero();
        assertThat(result.stdout()).contains("Result: PASSED");
    }

    private Invocation run(String apiVersion) throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Path catalog = directory.resolve("observations.yaml");
        Files.writeString(scenario, SCENARIO);
        Files.writeString(catalog, CATALOG.formatted(apiVersion));
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout, true), new PrintWriter(stderr, true)).run(new String[]{
                "test", "--scenario", scenario.toString(), "--observations", catalog.toString(),
                "--output", directory.resolve("reports").toString()});
        return new Invocation(exit, stdout.toString(), stderr.toString());
    }

    private record Invocation(int exit, String stdout, String stderr) {
    }
}
