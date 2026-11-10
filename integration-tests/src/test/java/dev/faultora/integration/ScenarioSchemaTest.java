package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.schema.SchemaValidator;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the published schema's frozen fields and compares representative
 * documents with Faultora's parser and structural validator.
 * <p>
 * The catalog validator covers the constraints exercised here. It does not
 * apply schemas under {@code additionalProperties}, so references are also
 * checked directly, including those used for named inputs.
 */
class ScenarioSchemaTest {
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern YAML_BLOCK = Pattern.compile("```ya?ml\\R(.*?)```", Pattern.DOTALL);

    /** Where each model record of the surface snapshot lives in the schema. */
    private static final Map<String, String> RECORDS = Map.of(
            "ScenarioDocument", "", "ScenarioMetadata", "metadata", "InputDeclaration", "inputDeclaration",
            "ScenarioStep", "step", "RetryPolicy", "retry", "Generate", "generate", "Condition", "condition",
            "FaultStep", "fault", "AssertionStep", "assertion");

    @Test
    void theSchemaDeclaresExactlyTheFrozenFields() throws Exception {
        JsonNode schema = schema();
        Map<String, Set<String>> frozen = new TreeMap<>();
        for (String line : Files.readAllLines(ROOT.resolve("faultora-spec/src/test/resources/v1-scenario-surface.txt"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String owner = line.substring(0, line.indexOf(':'));
            String record = owner.substring(0, owner.lastIndexOf('.'));
            frozen.computeIfAbsent(record, ignored -> new TreeSet<>()).add(owner.substring(owner.lastIndexOf('.') + 1));
        }
        assertThat(frozen.keySet()).as("every record of the frozen surface has a place in the schema")
                .containsExactlyInAnyOrderElementsOf(RECORDS.keySet());
        frozen.forEach((record, fields) -> {
            JsonNode definition = RECORDS.get(record).isEmpty() ? schema : schema.path("$defs").path(RECORDS.get(record));
            Set<String> declared = new TreeSet<>();
            definition.path("properties").fieldNames().forEachRemaining(declared::add);
            assertThat(declared).as("schema fields of %s", record).isEqualTo(fields);
            assertThat(definition.path("additionalProperties").asBoolean(true))
                    .as("%s refuses a field the format does not have, as the parser does", record).isFalse();
        });
    }

    @Test
    void everySchemaReferenceResolves() {
        JsonNode schema = schema();
        List<String> references = schema.findValuesAsText("$ref");
        assertThat(references).isNotEmpty();
        for (String reference : references) {
            assertThat(reference).startsWith("#/");
            assertThat(schema.at(reference.substring(1)).isObject()).as("schema reference %s", reference).isTrue();
        }
    }

    @Test
    void everyExampleAndDocumentedScenarioSatisfiesTheSchema() throws Exception {
        Map<String, String> examples = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("examples"))) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".yaml") || file.toString().endsWith(".yml"))
                    .sorted().toList()) {
                String yaml = read(path);
                JsonNode document = parse(yaml);
                if (document != null && "Scenario".equals(document.path("kind").asText())) {
                    examples.put(ROOT.relativize(path).toString(), yaml);
                }
            }
        }
        assertThat(examples).containsKey(Path.of("examples/payment-service/qualification/scenario.yaml").toString());
        examples.forEach(ScenarioSchemaTest::assertAccepted);

        Map<String, String> documented = new LinkedHashMap<>();
        List<Path> guides = new ArrayList<>(List.of(ROOT.resolve("README.md")));
        try (Stream<Path> docs = Files.walk(ROOT.resolve("docs"))) {
            docs.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".md"))
                    .sorted().forEach(guides::add);
        }
        for (Path guide : guides) {
            Matcher block = YAML_BLOCK.matcher(read(guide));
            for (int index = 1; block.find(); index++) {
                String yaml = block.group(1);
                if (yaml.contains("apiVersion: faultora.dev/v1\n") && yaml.contains("\nexecute:")
                        && yaml.contains("\nmetadata:")) {
                    documented.put(ROOT.relativize(guide) + " block " + index, yaml);
                }
            }
        }
        assertThat(documented).as("complete scenarios found in the documentation").hasSizeGreaterThanOrEqualTo(2);
        documented.forEach(ScenarioSchemaTest::assertAccepted);
    }

    @Test
    void documentsFaultoraRefusesDoNotSatisfyTheSchema() throws Exception {
        String valid = """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: refused}
                execute:
                  - id: read
                    operationId: read
                    retry: {maxAttempts: 2, backoffMultiplier: 1}
                """;
        assertAccepted("the base document", valid);
        assertRejected("retry without a multiplier", valid.replace(", backoffMultiplier: 1", ""));
        assertRejected("retry without attempts", valid.replace("maxAttempts: 2, ", ""));
        assertAccepted("a mixed-case duration suffix", valid + "timeout: 10Ms\n");

        String repeat = """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata: {name: repeat}
                execute:
                  - id: batch
                    type: repeat
                    count: 100
                    steps:
                      - id: read
                        operationId: read
                """;
        assertAccepted("100 repeat iterations", repeat);
        String forEachAtLimit = "forEach: [" + String.join(", ", Collections.nCopies(100, "1")) + "]";
        assertAccepted("100 repeat input items", repeat.replace("count: 100", forEachAtLimit));

        Map<String, String> refused = Map.ofEntries(
                entry("an unknown top-level section", valid + "steps: []\n"),
                entry("the with: key the retry guide once named", valid.replace("operationId: read\n",
                        "operationId: read\n    with: {key: one}\n")),
                entry("dependsOn as a string", valid.replace("operationId: read\n",
                        "operationId: read\n    dependsOn: other\n")),
                entry("an empty execute", valid.substring(0, valid.indexOf("execute:")) + "execute: []\n"),
                entry("no metadata name", valid.replace("{name: refused}", "{description: none}")),
                entry("another kind", valid.replace("kind: Scenario", "kind: Catalog")),
                entry("zero retry attempts", valid.replace("maxAttempts: 2", "maxAttempts: 0")),
                entry("an unknown step type", valid.replace("operationId: read\n", "operationId: read\n    type: loop\n")),
                entry("a malformed duration", valid + "timeout: 5 minutes\n"),
                entry("a space before the duration suffix", valid + "timeout: 10 ms\n"),
                entry("a zero duration as a number", valid + "timeout: 0\n"),
                entry("a zero duration with a suffix", valid + "timeout: 0s\n"),
                entry("a fault without duration", valid + "faults: [{id: delay, faultType: http-latency}]\n"),
                entry("too many repeat iterations", repeat.replace("count: 100", "count: 101")),
                entry("an empty repeat input list", repeat.replace("count: 100", "forEach: []")),
                entry("too many repeat input items", repeat.replace("count: 100",
                        forEachAtLimit.replace("]", ", 1]"))));
        refused.forEach(ScenarioSchemaTest::assertRejected);
    }

    private static void assertAccepted(String what, String yaml) {
        ParseResult<ScenarioDocument> result = parseAndValidate(yaml);
        assertThat(result.isSuccess()).as("Faultora accepts %s: %s", what, result.diagnostics()).isTrue();
        assertThat(violations(parse(yaml))).as("schema violations in %s", what).isEmpty();
    }

    private static void assertRejected(String what, String yaml) {
        assertThat(parseAndValidate(yaml).errors()).as("Faultora refuses %s", what).isNotEmpty();
        assertThat(violations(parse(yaml))).as("the schema refuses %s", what).isNotEmpty();
    }

    private static ParseResult<ScenarioDocument> parseAndValidate(String yaml) {
        ParseResult<ScenarioDocument> parsed = new ScenarioParser().parse(yaml);
        return parsed.isSuccess() ? new ScenarioValidator().validate(parsed.document()) : parsed;
    }

    private static List<SchemaValidator.Violation> violations(JsonNode document) {
        JsonNode schema = schema();
        Map<SchemaId, DataSchema> definitions = new LinkedHashMap<>();
        schema.path("$defs").properties().forEach(entry -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> definition = JSON.convertValue(entry.getValue(), Map.class);
            SchemaId id = new SchemaId(entry.getKey());
            definitions.put(id, new DataSchema(id, entry.getValue().path("type").asText(null), null, definition));
        });
        ObjectNode root = schema.deepCopy();
        root.remove("$defs");
        return new SchemaValidator(new SchemaCatalog(definitions)).validate(document, root);
    }

    private static JsonNode schema() {
        try {
            return JSON.readTree(ROOT.resolve("docs/schema/scenario-v1.schema.json").toFile());
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static JsonNode parse(String yaml) {
        try {
            return YAML.readTree(yaml);
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
    }
}
