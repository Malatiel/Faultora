package dev.faultora.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import dev.faultora.engine.journal.RunEventReader;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What docs/UPGRADE_ROLLBACK.md promises about moving between two releases,
 * checked against a verified previous release and a packaged candidate.
 * <p>
 * Every published payment-service scenario runs, destructive steps admitted since
 * several delete what they create, each time against a fresh
 * example API, once on the previous JAR and once on the next; the exit code,
 * every assertion's outcome and every node's terminal event must match, so a
 * rollback gives the same verdicts as the upgrade it undoes. Every run must
 * have been executed — exit 0 or 1, a journal from RUN_STARTED to a terminal
 * event with at least one node outcome — so two releases that refuse every
 * scenario alike do not count as agreeing. Each journal
 * either version wrote must be readable by the current reader, and both
 * versions must read the same runner status file the same way. With
 * {@code FAULTORA_CHECK_MACHINE_OUTPUT_V1=true}, also compare machine diagnostics
 * and preview actions across releases and match each JSON report to its journal.
 * <p>
 * Not part of the ordinary build, since it needs both packaged JARs: its
 * name keeps it out of discovery. Run it by naming it, with
 * {@code FAULTORA_PREVIOUS_JAR} and {@code FAULTORA_NEXT_JAR} set.
 */
class UpgradeRollbackCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final int EXIT_PASS = 0;
    private static final int EXIT_ASSERTION_FAILED = 1;

    @Test
    void bothReleasesGiveTheSameVerdictsAndReadEachOthersFiles() throws Exception {
        Path previous = jar("FAULTORA_PREVIOUS_JAR");
        Path next = jar("FAULTORA_NEXT_JAR");
        boolean checkMachineOutput = Boolean.parseBoolean(System.getenv("FAULTORA_CHECK_MACHINE_OUTPUT_V1"));
        String beforeDigest = digest(previous);
        String afterDigest = digest(next);
        if (checkMachineOutput) {
            assertThat(afterDigest).as("the comparison must use two distinct JARs").isNotEqualTo(beforeDigest);
        }
        Path out = ROOT.resolve("integration-tests/target/upgrade-rollback");
        deleteRecursively(out);
        Files.createDirectories(out);

        List<String> rows = new ArrayList<>();
        String basicColumns = "| Scenario | Exit (previous / next) | Assertions | Nodes | Journals readable |";
        rows.add(checkMachineOutput ? basicColumns + " Diagnostics | Report matches journal |" : basicColumns);
        rows.add(checkMachineOutput ? "|---|---|---|---|---|---|---|" : "|---|---|---|---|---|");
        List<String> differences = new ArrayList<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("examples/payment-service/scenarios"))) {
            for (Path scenario : files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList()) {
                String name = scenario.getFileName().toString();
                Run before = run(previous, scenario, out.resolve("previous").resolve(name), checkMachineOutput);
                Run after = run(next, scenario, out.resolve("next").resolve(name), checkMachineOutput);
                boolean same = before.equals(after);
                if (!same) {
                    differences.add(name + ": " + before + " vs " + after);
                }
                String row = "| " + name + " | " + before.exit() + " / " + after.exit() + " | "
                        + (before.assertions().equals(after.assertions()) ? "same, " + after.assertions().size() : "DIFFER")
                        + " | " + (before.nodes().equals(after.nodes()) ? "same, " + after.nodes().size() : "DIFFER")
                        + " | yes |";
                if (checkMachineOutput) {
                    row += " " + (before.diagnostics().equals(after.diagnostics()) ? "same" : "DIFFER") + " | yes |";
                }
                rows.add(row);
            }
        }

        rows.add("");
        if (checkMachineOutput) {
            JsonNode previousPreview = preview(previous, out.resolve("preview-previous"));
            JsonNode nextPreview = preview(next, out.resolve("preview-next"));
            assertThat(nextPreview).as("the smoke scenario's plan actions and their shapes").isEqualTo(previousPreview);
            rows.add("Smoke scenario preview actions: same, " + nextPreview.size());
        }

        // The status a runner writes, read by both releases: a rollback must
        // not leave a probe unable to read the file the newer runner left.
        Path status = out.resolve("health.json");
        Files.writeString(status, "{\"state\":\"WAITING\",\"runnerId\":\"probe\",\"updatedAtEpochMs\":"
                + System.currentTimeMillis() + ",\"registered\":true,\"currentRunId\":null,\"runsServed\":0}\n");
        int previousHealth = cli(previous, out.resolve("health-previous.log"), false, "health", "--file", status.toString(),
                "--require-registered");
        int nextHealth = cli(next, out.resolve("health-next.log"), false, "health", "--file", status.toString(),
                "--require-registered");
        rows.add("Runner status file read by previous / next: exit " + previousHealth + " / " + nextHealth);

        String table = String.join(System.lineSeparator(), rows) + System.lineSeparator();
        Files.writeString(out.resolve("result.md"), "Previous: " + previous.getFileName() + " (" + beforeDigest + ")"
                + System.lineSeparator() + "Candidate: " + next.getFileName() + " (" + afterDigest + ")"
                + System.lineSeparator() + System.lineSeparator() + table);
        System.out.println(table);
        assertThat(differences).as("verdicts or diagnostic codes that changed between the releases").isEmpty();
        assertThat(previousHealth).isZero();
        assertThat(nextHealth).isZero();
    }

    private static Run run(Path jar, Path scenario, Path output, boolean checkMachineOutput) throws Exception {
        Files.createDirectories(output.getParent());
        PaymentApi api = new PaymentApi();
        api.start();
        int exit;
        Path stdout = output.resolveSibling(output.getFileName() + (checkMachineOutput ? ".json" : ".log"));
        try {
            List<String> arguments = new ArrayList<>();
            if (checkMachineOutput) {
                arguments.add("--diagnostics=json");
            }
            arguments.addAll(List.of("test",
                    "--scenario", scenario.toString(), "--openapi", ExampleFixtures.openApi().toString(),
                    "--target", api.baseUrl(), "--allow-private", "--allow-destructive",
                    "--format", "json", "--output", output.toString()));
            if (checkMachineOutput) {
                arguments.addAll(List.of("--seed", "42"));
            }
            exit = cli(jar, stdout, checkMachineOutput, arguments.toArray(String[]::new));
        } finally {
            api.stop();
        }
        // Two releases refusing a scenario the same way would match too: only
        // a run the engine executed counts, so both must pass or fail it on
        // its assertions and leave a journal from start to terminal event.
        assertThat(exit).as("exit of %s on %s, see its captured output", scenario.getFileName(), jar.getFileName())
                .isIn(EXIT_PASS, EXIT_ASSERTION_FAILED);
        List<Diagnostic> diagnostics = checkMachineOutput ? diagnostics(stdout, exit) : List.of();
        Path journal = output.resolve("events.ndjson");
        assertThat(journal).as("journal of %s on %s", scenario.getFileName(), jar.getFileName()).isRegularFile();
        Map<String, String> assertions = new TreeMap<>();
        Map<String, String> nodes = new TreeMap<>();
        List<String> types = new ArrayList<>();
        var journalEvents = JSON.createArrayNode();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            if (line.isBlank() && !checkMachineOutput) {
                continue;
            }
            RunEventReader.readLine(line);
            JsonNode event = JSON.readTree(line);
            journalEvents.add(event);
            String type = event.path("eventType").asText();
            types.add(type);
            String node = event.path("nodeId").asText();
            if (type.equals("ASSERTION_EVALUATED")) {
                assertions.put(node, event.path("outcome").asText());
            } else if (type.equals("NODE_COMPLETED") || type.equals("NODE_FAILED") || type.equals("NODE_SKIPPED")) {
                nodes.put(node, type);
            }
        }
        assertThat(types).as("events of %s on %s", scenario.getFileName(), jar.getFileName())
                .startsWith("RUN_STARTED").containsAnyOf("NODE_COMPLETED", "NODE_FAILED");
        assertThat(types.get(types.size() - 1)).isIn("RUN_COMPLETED", "RUN_FAILED");
        if (checkMachineOutput) {
            assertThat(JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(Files.readString(output.resolve("report.json"))))
                    .as("JSON report matches its journal for %s on %s", scenario.getFileName(), jar.getFileName())
                    .isEqualTo(journalEvents);
        }
        return new Run(exit, assertions, nodes, diagnostics);
    }

    private static List<Diagnostic> diagnostics(Path stdout, int exit) throws Exception {
        JsonNode result = machineResult(stdout, exit);
        assertThat(result.path("preview").isNull()).isTrue();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JsonNode diagnostic : result.path("diagnostics")) {
            assertThat(diagnostic.path("code").textValue()).isNotBlank();
            assertThat(diagnostic.path("severity").textValue()).isIn("ERROR", "WARNING", "INFO");
            assertThat(diagnostic.path("node").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.NULL);
            assertThat(diagnostic.path("path").getNodeType()).isIn(JsonNodeType.STRING, JsonNodeType.NULL);
            assertThat(diagnostic.path("hint").textValue()).isNotBlank();
            diagnostics.add(new Diagnostic(diagnostic.path("code").textValue(),
                    diagnostic.path("severity").textValue(), diagnostic.path("node").textValue()));
        }
        if (exit == EXIT_ASSERTION_FAILED) {
            assertThat(diagnostics).anyMatch(diagnostic -> diagnostic.severity().equals("ERROR"));
        }
        return diagnostics;
    }

    private static JsonNode preview(Path jar, Path output) throws Exception {
        Path stdout = output.resolveSibling(output.getFileName() + ".json");
        int exit = cli(jar, stdout, true, "--diagnostics=json", "test", "--preview",
                "--scenario", ExampleFixtures.scenario("passing.yaml").toString(),
                "--openapi", ExampleFixtures.openApi().toString(), "--allow-private", "--seed", "42",
                "--output", output.toString());
        assertThat(exit).isEqualTo(EXIT_PASS);
        JsonNode plan = machineResult(stdout, exit).path("preview");
        assertThat(plan.path("compiled").booleanValue()).isTrue();
        assertThat(plan.path("actions").isArray()).isTrue();
        assertThat(plan.path("actions").size()).isPositive();
        assertThat(output).doesNotExist();
        return plan.path("actions");
    }

    private static JsonNode machineResult(Path stdout, int exit) throws Exception {
        String text = Files.readString(stdout);
        assertThat(text.lines().count()).as("one machine result in %s", stdout.getFileName()).isEqualTo(1);
        assertThat(text).endsWith("\n");
        JsonNode result = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        assertThat(result.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(result.path("schemaVersion").bigIntegerValue()).isEqualTo(java.math.BigInteger.ONE);
        assertThat(result.path("command").textValue()).isEqualTo("test");
        assertThat(result.path("exitCode").isIntegralNumber()).isTrue();
        assertThat(result.path("exitCode").bigIntegerValue()).isEqualTo(java.math.BigInteger.valueOf(exit));
        assertThat(result.path("diagnostics").isArray()).isTrue();
        return result;
    }

    private static int cli(Path jar, Path log, boolean machineOutput, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(log.toFile());
        if (machineOutput) {
            builder.redirectError(log.resolveSibling(log.getFileName() + ".stderr").toFile());
        } else {
            builder.redirectErrorStream(true);
        }
        return BoundedProcess.run(builder, Duration.ofMinutes(3), process -> { });
    }

    private static Path jar(String variable) {
        String value = System.getenv(variable);
        assertThat(value).as("%s names a verified release or locally packaged candidate JAR", variable).isNotBlank();
        Path jar = Path.of(value).toAbsolutePath();
        assertThat(jar).isRegularFile();
        return jar;
    }

    private static String digest(Path jar) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(jar), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private record Diagnostic(String code, String severity, String node) { }

    private record Run(int exit, Map<String, String> assertions, Map<String, String> nodes,
                       List<Diagnostic> diagnostics) { }
}
