package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunOwnershipTest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path scenario() throws Exception {
        Path path = directory.resolve("scenario.yaml");
        Files.writeString(path, """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: ownership}
                faults:
                  - id: delay
                    faultType: http-latency
                    duration: 1s
                    params: {delayMs: 1}
                execute:
                  - id: pause
                    type: wait
                    timeout: 1ms
                """);
        return path;
    }

    private int execute(Path scenario, Path output) {
        var cli = new FaultoraCli(new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        return cli.run(new String[]{"test", "--scenario", scenario.toString(),
                "--seed", "42", "--format", "json", "--output", output.toString()});
    }

    private List<JsonNode> events(Path output) throws Exception {
        var events = new java.util.ArrayList<JsonNode>();
        for (String line : Files.readAllLines(output.resolve("events.ndjson"))) {
            events.add(MAPPER.readTree(line));
        }
        return events;
    }

    @Test
    void replayingASeedGetsDistinctIdentitiesJournalsAndCleanup() throws Exception {
        Path scenario = scenario();
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        assertThat(execute(scenario, first)).isEqualTo(FaultoraCli.EXIT_PASS);
        String original = Files.readString(first.resolve("events.ndjson"));
        assertThat(execute(scenario, second)).isEqualTo(FaultoraCli.EXIT_PASS);
        var firstEvents = events(first);
        var secondEvents = events(second);
        assertThat(firstEvents.getFirst().path("seed").asLong()).isEqualTo(42);
        assertThat(secondEvents.getFirst().path("seed").asLong()).isEqualTo(42);
        assertThat(firstEvents.getFirst().path("runId")).isNotEqualTo(secondEvents.getFirst().path("runId"));
        assertThat(firstEvents.stream().filter(e -> e.path("eventType").asText().equals("FAULT_ROLLED_BACK")))
                .hasSize(1);
        assertThat(secondEvents.stream().filter(e -> e.path("eventType").asText().equals("FAULT_ROLLED_BACK")))
                .hasSize(1);
        assertThat(Files.readString(first.resolve("events.ndjson"))).isEqualTo(original);
        assertThat(first.resolve("report.json")).exists();
        assertThat(second.resolve("report.json")).exists();
    }

    @Test
    void reusingAnExplicitOutputDirectoryRefusesBeforeTruncatingAnything() throws Exception {
        Path scenario = scenario();
        Path output = directory.resolve("shared");
        assertThat(execute(scenario, output)).isEqualTo(FaultoraCli.EXIT_PASS);
        String journal = Files.readString(output.resolve("events.ndjson"));
        String report = Files.readString(output.resolve("report.json"));
        assertThat(execute(scenario, output)).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(Files.readString(output.resolve("events.ndjson"))).isEqualTo(journal);
        assertThat(Files.readString(output.resolve("report.json"))).isEqualTo(report);
    }

    @Test
    void legacyUnownedJournalIsPreservedToo() throws Exception {
        Path scenario = scenario();
        Path output = Files.createDirectory(directory.resolve("legacy"));
        Files.writeString(output.resolve("events.ndjson"), "{\"stale\":true}\n");
        assertThat(execute(scenario, output)).isEqualTo(FaultoraCli.EXIT_RUNNER_FAILURE);
        assertThat(Files.readString(output.resolve("events.ndjson"))).isEqualTo("{\"stale\":true}\n");
        assertThat(output.resolve("run-owner")).doesNotExist();
    }

    @Test
    void defaultOutputIsAllocatedAfterTheIndependentRunIdentity() {
        assertThat(TestOptions.parse(List.of("--scenario", "synthetic.yaml", "--seed", "42")).outputDir())
                .isNull();
    }
}
