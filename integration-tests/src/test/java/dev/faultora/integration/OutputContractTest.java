package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.engine.journal.RunEventReader;
import dev.faultora.reporting.JsonRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path directory;

    private JsonNode fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/output/v1/" + name + ".json")) {
            assertThat(input).isNotNull();
            return MAPPER.readTree(input);
        }
    }

    @Test
    void everyEventKeepsItsFrozenJournalAndJsonReportShape() throws Exception {
        JsonNode expected = fixture("events");
        List<RunEvent> events = new ArrayList<>();
        var types = new HashSet<Class<?>>();
        for (JsonNode event : expected) {
            RunEvent decoded = MAPPER.treeToValue(event, RunEvent.class);
            events.add(decoded);
            types.add(decoded.getClass());
        }
        assertThat(types).containsExactlyInAnyOrder(RunEvent.class.getPermittedSubclasses());
        Path path = directory.resolve("events.ndjson");
        try (RunJournal journal = new RunJournal(path, true)) {
            for (RunEvent event : events) {
                journal.append(event);
            }
        }
        var lines = Files.readAllLines(path);
        assertThat(lines).hasSize(expected.size());
        for (int index = 0; index < lines.size(); index++) {
            assertThat(MAPPER.readTree(lines.get(index))).isEqualTo(expected.get(index));
            assertThat(RunEventReader.readLine(lines.get(index))).contains(events.get(index));
        }
        StringWriter report = new StringWriter();
        new JsonRenderer().render(events, report);
        assertThat(MAPPER.readTree(report.toString())).isEqualTo(expected);
    }

    @Test
    void equivalentMapInsertionOrdersProduceIdenticalJournalBytes() throws Exception {
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("a", 1);
        first.put("b", 2);
        var second = new java.util.LinkedHashMap<String, Object>();
        second.put("b", 2);
        second.put("a", 1);
        for (var entry : java.util.Map.of("first", first, "second", second).entrySet()) {
            try (var journal = new RunJournal(directory.resolve(entry.getKey() + ".ndjson"), true)) {
                journal.append(new RunEvent.RunStarted("RUN_STARTED", 1000,
                        new dev.faultora.model.identifier.RunId("synthetic-run"),
                        "sha256:scenario", "sha256:catalog", 42, entry.getValue()));
            }
        }
        assertThat(Files.readString(directory.resolve("first.ndjson")))
                .isEqualTo(Files.readString(directory.resolve("second.ndjson")));
    }

    @Test
    void closedEnumsRequireAnExplicitCompatibilityReview() throws Exception {
        JsonNode enums = fixture("enums");
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(RunResult.Status.values())))
                .isEqualTo(enums.path("runStatus"));
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(
                dev.faultora.model.catalog.NormalizedError.ErrorCategory.values())))
                .isEqualTo(enums.path("errorCategory"));
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(
                dev.faultora.spi.result.AssertionResult.Outcome.values())))
                .isEqualTo(enums.path("assertionOutcome"));
    }

    @Test
    void everyRunStatusKeepsItsFrozenResultAndNodeShape() throws Exception {
        JsonNode expected = fixture("results");
        var statuses = new HashSet<RunResult.Status>();
        for (JsonNode result : expected) {
            RunResult decoded = MAPPER.treeToValue(result, RunResult.class);
            statuses.add(decoded.status());
            JsonNode encoded = MAPPER.readTree(MAPPER.writeValueAsString(decoded));
            assertThat(encoded).isEqualTo(result);
        }
        assertThat(statuses).containsExactlyInAnyOrder(RunResult.Status.values());
    }

    @Test
    void readerAcceptsAdditiveFieldsIncludingNestedErrorFields() throws Exception {
        for (JsonNode fixture : fixture("events")) {
            ObjectNode extended = fixture.deepCopy();
            extended.putObject("futureField").put("value", true);
            if (extended.get("error") instanceof ObjectNode error) {
                error.put("futureErrorField", "synthetic");
            }
            assertThat(RunEventReader.readLine(extended.toString()))
                    .contains(MAPPER.treeToValue(fixture, RunEvent.class));
        }
    }

    @Test
    void readerSkipsUnknownTypesWithoutLosingFollowingKnownEvents() throws Exception {
        assertThat(RunEventReader.readLine(
                "{\"eventType\":\"FUTURE_EVENT\",\"timestamp\":1000,\"runId\":\"synthetic-run\"}"))
                .isEmpty();
        for (JsonNode event : fixture("events")) {
            assertThat(RunEventReader.readLine(event.toString())).isPresent();
        }
    }

    @Test
    void missingKnownFieldsAreRefusedRatherThanReplacedWithDefaults() throws Exception {
        for (JsonNode fixture : fixture("events")) {
            var fields = new ArrayList<String>();
            fixture.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                ObjectNode incomplete = fixture.deepCopy();
                incomplete.remove(field);
                assertThatThrownBy(() -> RunEventReader.readLine(incomplete.toString()))
                        .as(fixture.path("eventType").asText() + "." + field)
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void renamingRemovingOrChangingTheTypeOfAKnownFieldIsRejected() throws Exception {
        ObjectNode event = fixture("events").get(1).deepCopy();
        ObjectNode renamed = event.deepCopy();
        renamed.set("nodes", renamed.remove("totalNodes"));
        ObjectNode mistyped = event.deepCopy();
        mistyped.put("totalNodes", "1");
        ObjectNode removed = event.deepCopy();
        removed.remove("totalNodes");
        for (ObjectNode changed : List.of(renamed, mistyped, removed)) {
            assertThatThrownBy(() -> RunEventReader.readLine(changed.toString()))
                    .isInstanceOf(IOException.class);
            assertThat(changed).isNotEqualTo(event);
        }
    }

    @Test
    void malformedFieldsAndTrailingInputAreRefusedWithoutEchoingValues() {
        for (String input : List.of("[]", "null", "{", "{} {}",
                "{\"eventType\":\"RUN_COMPLETED\",\"timestamp\":\"SYNTHETIC_PRIVATE_VALUE\",\"runId\":\"r\"}",
                "{\"eventType\":\"FUTURE\",\"timestamp\":1,\"runId\":null}")) {
            assertThatThrownBy(() -> RunEventReader.readLine(input))
                    .isInstanceOf(IOException.class)
                    .hasMessageNotContaining("SYNTHETIC_PRIVATE_VALUE");
        }
    }
}
