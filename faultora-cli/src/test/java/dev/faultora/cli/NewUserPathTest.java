package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person meets on their first run, and what they are told to do about it.
 * <p>
 * Each case here was a dead end: a generated scenario the next command refused,
 * a localhost target that failed every step as "operation failed", a missing ID
 * reported as an internal error. None of them needs a target to reproduce —
 * the refusals all happen before a request would be sent.
 */
class NewUserPathTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Four operations: a list, a create, a read by ID and a delete. */
    private static final String OPENAPI = """
            openapi: 3.0.3
            info: { title: Payments, version: 1.0.0 }
            servers: [{ url: "http://api.example.com" }]
            paths:
              /payments:
                get:
                  operationId: list-payments
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { type: array, items: { type: object } } } }
                post:
                  operationId: create-payment
                  requestBody:
                    required: true
                    content: { application/json: { schema: { type: object, properties: { amount: { type: integer } } } } }
                  responses:
                    '201':
                      description: created
                      content: { application/json: { schema: { type: object } } }
              /payments/{paymentId}:
                get:
                  operationId: get-payment
                  parameters:
                    - { name: paymentId, in: path, required: true, schema: { type: string } }
                  responses:
                    '200': { description: ok, content: { application/json: { schema: { type: object } } } }
                delete:
                  operationId: delete-payment
                  parameters:
                    - { name: paymentId, in: path, required: true, schema: { type: string } }
                  responses:
                    '204': { description: deleted }
            """;

    @TempDir Path directory;

    private record Invocation(int exit, String stdout, String stderr) { }

    private Invocation run(String... args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(args);
        return new Invocation(exit, stdout.toString(), stderr.toString());
    }

    private Path openApi() throws Exception {
        return Files.writeString(directory.resolve("openapi.yaml"), OPENAPI);
    }

    private Path scenario(String steps) throws Exception {
        return Files.writeString(directory.resolve("scenario.yaml"), """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: first-run}
                execute:
                """ + steps);
    }

    @Test
    void initGeneratesOnlyStepsThatRunAndSaysWhyTheOthersAreLeftOut() throws Exception {
        var init = run("init", "--from-openapi", openApi().toString(), "--output", directory.resolve("starter").toString());

        assertThat(init.exit()).isEqualTo(FaultoraCli.EXIT_PASS);
        String generated = Files.readString(directory.resolve("starter/scenario.yaml"));
        assertThat(generated)
                .contains("operationId: list-payments")
                .doesNotContain("operationId: get-payment", "operationId: delete-payment",
                        "operationId: create-payment")
                .contains("#   get-payment: needs paymentId under inputs")
                .contains("#   delete-payment: destructive; runs only with --allow-destructive")
                .contains("expected: 200");
        assertThat(init.stdout()).contains("Next:", "--preview", "--allow-private");
    }

    @Test
    void anOperationThatNeedsARequestBodyIsLeftForTheAuthorToFillIn() throws Exception {
        // Generated as a step, the POST went out with an empty body; an API
        // that checks its input answered 400 and the starter scenario failed.
        run("init", "--from-openapi", openApi().toString(), "--output", directory.resolve("starter").toString());

        assertThat(Files.readString(directory.resolve("starter/scenario.yaml")))
                .doesNotContain("operationId: create-payment")
                .contains("#   create-payment: needs a request body under inputs");
    }

    @Test
    void pathsInTheNextCommandsAreQuotedSoNothingInThemIsExpanded() {
        // Double quotes still expand $name in PowerShell and POSIX shells, so a
        // file called "catalog $X.yaml" became another path once pasted. Single
        // quotes are literal in both; each escapes its own quote differently.
        Path awkward = Path.of("dir with space", "catalog $X it's.yaml");

        assertThat(InitCommand.quoted(awkward, true))
                .isEqualTo("'" + awkward.toString().replace("'", "''") + "'");
        assertThat(InitCommand.quoted(awkward, false))
                .isEqualTo("'" + awkward.toString().replace("'", "'\\''") + "'");
        assertThat(InitCommand.quoted(Path.of("plain-scenario.yaml"), false)).isEqualTo("plain-scenario.yaml");
        assertThat(InitCommand.quoted(Path.of("plain-scenario.yaml"), true)).isEqualTo("plain-scenario.yaml");
    }

    @Test
    void aBackslashIsQuotedForAPosixShellButIsAPlainSeparatorForPowerShell() {
        // Unquoted, sh read catalog\literal.yaml as catalogliteral.yaml.
        Path linuxName = Path.of("catalog\\literal.yaml");
        Path windowsPath = Path.of("C:\\work\\catalog.yaml");

        assertThat(InitCommand.quoted(linuxName, false)).isEqualTo("'" + linuxName + "'");
        assertThat(InitCommand.quoted(windowsPath, true)).isEqualTo(windowsPath.toString());
    }

    @Test
    void aMissingSqlBindVariableIsNotDescribedAsAUrlPath() throws Exception {
        // PARAMETER_MISSING is also what an observation reports for an unbound
        // :name, and the hint said "path" to someone who had written SQL.
        Path observations = Files.writeString(directory.resolve("observations.yaml"), """
                apiVersion: faultora.dev/v1
                kind: Observations
                servers:
                  ledger: { url: "jdbc:h2:mem:new-user-path" }
                observations:
                  entries-for: { server: ledger, sql: "SELECT :paymentId AS payment_id" }
                """);
        Path scenario = scenario("""
                  - id: entries
                    operationId: entries-for
                """);

        var tested = run("--diagnostics=json", "test", "--scenario", scenario.toString(),
                "--observations", observations.toString(), "--output", directory.resolve("json").toString());
        var human = run("test", "--scenario", scenario.toString(),
                "--observations", observations.toString(), "--output", directory.resolve("human").toString());

        assertThat(JSON.readTree(tested.stdout()).path("diagnostics")).anySatisfy(diagnostic -> {
            assertThat(diagnostic.path("code").asText()).isEqualTo("PARAMETER_MISSING");
            assertThat(diagnostic.path("hint").asText()).doesNotContain("path");
        });
        assertThat(human.exit()).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(human.stderr()).contains("bind variable", "under the step's inputs")
                .doesNotContain("whose path has");
    }


    @Test
    void theGeneratedScenarioPassesValidateAndCompilesUnderTheDefaultPolicy() throws Exception {
        Path catalog = openApi();
        Path starter = directory.resolve("starter");
        run("init", "--from-openapi", catalog.toString(), "--output", starter.toString());
        Path generated = starter.resolve("scenario.yaml");

        var validated = run("validate", "--scenario", generated.toString(), "--openapi", catalog.toString());
        var previewed = run("test", "--preview", "--scenario", generated.toString(),
                "--openapi", catalog.toString(), "--target", "http://api.example.com");

        assertThat(validated.exit()).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(validated.stdout()).contains("Next: test --preview");
        assertThat(previewed.exit()).as(previewed.stderr()).isEqualTo(FaultoraCli.EXIT_PASS);
    }

    @Test
    void initDoesNotOverwriteAScenarioThatIsAlreadyThere() throws Exception {
        Path existing = Files.writeString(directory.resolve("scenario.yaml"), "edited by hand\n");

        var init = run("init", "--from-openapi", openApi().toString(), "--output", directory.toString());

        assertThat(init.exit()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
        assertThat(init.stderr()).contains("already exists", "--output");
        assertThat(Files.readString(existing)).isEqualTo("edited by hand\n");
    }

    @Test
    void aLocalTargetWithoutAllowPrivateIsNamedAsTheDestinationPolicy() throws Exception {
        Path scenario = scenario("""
                  - id: list
                    operationId: list-payments
                """);

        var tested = run("test", "--scenario", scenario.toString(), "--openapi", openApi().toString(),
                "--target", "http://127.0.0.1:1", "--output", directory.resolve("out").toString());

        assertThat(tested.exit()).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(tested.stderr()).contains("refused by the destination policy", "--allow-private");
        assertThat(tested.stdout()).contains("1 nodes (1 failed)").doesNotContain("Node execution failed");
        assertThat(Files.readString(directory.resolve("out/events.ndjson")))
                .contains("\"code\":\"DESTINATION_BLOCKED\"").doesNotContain("EXECUTION_ERROR");
    }

    @Test
    void theMachineDiagnosticNamesTheDestinationPolicy() throws Exception {
        Path scenario = scenario("""
                  - id: list
                    operationId: list-payments
                """);

        var tested = run("--diagnostics=json", "test", "--scenario", scenario.toString(),
                "--openapi", openApi().toString(), "--target", "http://127.0.0.1:1",
                "--output", directory.resolve("out").toString());

        JsonNode diagnostics = JSON.readTree(tested.stdout()).path("diagnostics");
        assertThat(diagnostics).anySatisfy(diagnostic -> {
            assertThat(diagnostic.path("code").asText()).isEqualTo("DESTINATION_BLOCKED");
            assertThat(diagnostic.path("node").asText()).isEqualTo("list");
            assertThat(diagnostic.path("hint").asText()).contains("--allow-private");
        });
    }

    @Test
    void aStepWithoutItsPathParameterIsToldWhatIsMissing() throws Exception {
        Path scenario = scenario("""
                  - id: read
                    operationId: get-payment
                """);

        var tested = run("test", "--scenario", scenario.toString(), "--openapi", openApi().toString(),
                "--target", "http://127.0.0.1:1", "--allow-private",
                "--output", directory.resolve("out").toString());

        assertThat(tested.exit()).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(tested.stderr()).contains("an ID in a URL path", "under the step's inputs");
        assertThat(Files.readString(directory.resolve("out/events.ndjson")))
                .contains("\"code\":\"PARAMETER_MISSING\"");
    }

    @Test
    void aDestructiveStepIsRefusedWithTheFlagThatPermitsIt() throws Exception {
        Path scenario = scenario("""
                  - id: remove
                    operationId: delete-payment
                    inputs: {paymentId: synthetic}
                """);
        List<String> args = new ArrayList<>(List.of("test", "--scenario", scenario.toString(),
                "--openapi", openApi().toString(), "--target", "http://127.0.0.1:1"));

        var refused = run(args.toArray(String[]::new));

        assertThat(refused.exit()).isEqualTo(FaultoraCli.EXIT_INVALID_CONFIG);
        assertThat(refused.stderr()).contains("Plan compilation failed", "--allow-destructive");
    }

    @Test
    void aPassingSummaryCountsAssertionsAsAssertions() throws Exception {
        Path scenario = scenario("""
                  - id: pause
                    type: wait
                    timeout: 1ms
                """);

        var tested = run("test", "--scenario", scenario.toString(),
                "--output", directory.resolve("out").toString());

        assertThat(tested.exit()).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(tested.stdout()).contains("Result: PASSED — 1 nodes, 0 passed assertions, 0 failed assertions");
        assertThat(tested.stderr()).doesNotContain("Hint:");
    }
}
