package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.journal.RunEventReader;
import dev.faultora.examples.payment.PaymentApi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What docs/UPGRADE_ROLLBACK.md promises about moving between two releases,
 * checked against two release JARs a person has already verified.
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
 * versions must read the same runner status file the same way.
 * <p>
 * Not part of the ordinary build, since it needs the two downloaded JARs: its
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
        Path out = ROOT.resolve("integration-tests/target/upgrade-rollback");
        deleteRecursively(out);
        Files.createDirectories(out);

        List<String> rows = new ArrayList<>();
        rows.add("| Scenario | Exit (previous / next) | Assertions | Nodes | Journals readable |");
        rows.add("|---|---|---|---|---|");
        List<String> differences = new ArrayList<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("examples/payment-service/scenarios"))) {
            for (Path scenario : files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList()) {
                String name = scenario.getFileName().toString();
                Run before = run(previous, scenario, out.resolve("previous").resolve(name));
                Run after = run(next, scenario, out.resolve("next").resolve(name));
                boolean same = before.exit() == after.exit() && before.assertions().equals(after.assertions())
                        && before.nodes().equals(after.nodes());
                if (!same) {
                    differences.add(name + ": " + before + " vs " + after);
                }
                rows.add("| " + name + " | " + before.exit() + " / " + after.exit() + " | "
                        + (before.assertions().equals(after.assertions()) ? "same, " + after.assertions().size() : "DIFFER")
                        + " | " + (before.nodes().equals(after.nodes()) ? "same, " + after.nodes().size() : "DIFFER")
                        + " | " + (before.journalReadable() && after.journalReadable() ? "yes" : "NO") + " |");
                assertThat(before.journalReadable() && after.journalReadable()).as("journals of %s", name).isTrue();
            }
        }

        // The status a runner writes, read by both releases: a rollback must
        // not leave a probe unable to read the file the newer runner left.
        Path status = out.resolve("health.json");
        Files.writeString(status, "{\"state\":\"WAITING\",\"runnerId\":\"probe\",\"updatedAtEpochMs\":"
                + System.currentTimeMillis() + ",\"registered\":true,\"currentRunId\":null,\"runsServed\":0}\n");
        int previousHealth = cli(previous, out.resolve("health-previous.log"), "health", "--file", status.toString(),
                "--require-registered");
        int nextHealth = cli(next, out.resolve("health-next.log"), "health", "--file", status.toString(),
                "--require-registered");
        rows.add("");
        rows.add("Runner status file read by previous / next: exit " + previousHealth + " / " + nextHealth);

        String table = String.join(System.lineSeparator(), rows) + System.lineSeparator();
        Files.writeString(out.resolve("result.md"), "Previous " + previous.getFileName() + ", next "
                + next.getFileName() + System.lineSeparator() + System.lineSeparator() + table);
        System.out.println(table);
        assertThat(differences).as("verdicts that changed between the releases").isEmpty();
        assertThat(previousHealth).isZero();
        assertThat(nextHealth).isZero();
    }

    private static Run run(Path jar, Path scenario, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        PaymentApi api = new PaymentApi();
        api.start();
        int exit;
        try {
            exit = cli(jar, output.resolveSibling(output.getFileName() + ".log"), "test",
                    "--scenario", scenario.toString(), "--openapi", ExampleFixtures.openApi().toString(),
                    "--target", api.baseUrl(), "--allow-private", "--allow-destructive",
                    "--format", "json", "--output", output.toString());
        } finally {
            api.stop();
        }
        // Two releases refusing a scenario the same way would match too: only
        // a run the engine executed counts, so both must pass or fail it on
        // its assertions and leave a journal from start to terminal event.
        assertThat(exit).as("exit of %s on %s, see its log", scenario.getFileName(), jar.getFileName())
                .isIn(EXIT_PASS, EXIT_ASSERTION_FAILED);
        Path journal = output.resolve("events.ndjson");
        assertThat(journal).as("journal of %s on %s", scenario.getFileName(), jar.getFileName()).isRegularFile();
        Map<String, String> assertions = new TreeMap<>();
        Map<String, String> nodes = new TreeMap<>();
        List<String> types = new ArrayList<>();
        boolean readable = true;
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                RunEventReader.readLine(line);
            } catch (Exception unreadable) {
                readable = false;
            }
            JsonNode event = JSON.readTree(line);
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
        return new Run(exit, assertions, nodes, readable);
    }

    private static int cli(Path jar, Path log, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        return BoundedProcess.run(builder, Duration.ofMinutes(3), process -> { });
    }

    private static Path jar(String variable) {
        String value = System.getenv(variable);
        assertThat(value).as("%s names a verified release JAR", variable).isNotBlank();
        Path jar = Path.of(value).toAbsolutePath();
        assertThat(jar).isRegularFile();
        return jar;
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

    private record Run(int exit, Map<String, String> assertions, Map<String, String> nodes, boolean journalReadable) { }
}
