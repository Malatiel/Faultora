package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.schema.SchemaCatalog;
import dev.faultora.schema.SchemaValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published JSON Schema for {@code faultora.dev/v1} says what Faultora
 * reads, so an editor or an AI assistant that trusts it writes documents
 * Faultora accepts.
 * <p>
 * Its fields are held to the frozen surface snapshot the model is checked
 * against, so a field added to the format fails here until the schema says
 * so too. Every example scenario and every complete scenario the guides show
 * must satisfy it, and documents Faultora refuses — an unknown field, a
 * misspelt section, a wrong type — must not.
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
    void everyExampleAndDocumentedScenarioSatisfiesTheSchema() throws Exception {
        Map<String, String> documents = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("examples"))) {
            files.filter(path -> path.toString().endsWith(".yaml") && path.getParent().endsWith("scenarios"))
                    .sorted().forEach(path -> documents.put(ROOT.relativize(path).toString(), read(path)));
        }
        List<Path> guides = new ArrayList<>(List.of(ROOT.resolve("README.md")));
        try (Stream<Path> docs = Files.list(ROOT.resolve("docs"))) {
            docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(guides::add);
        }
        for (Path guide : guides) {
            Matcher block = YAML_BLOCK.matcher(read(guide));
            for (int index = 1; block.find(); index++) {
                String yaml = block.group(1);
                if (yaml.contains("apiVersion: faultora.dev/v1\n") && yaml.contains("\nexecute:")
                        && yaml.contains("\nmetadata:")) {
                    documents.put(ROOT.relativize(guide) + " block " + index, yaml);
                }
            }
        }
        assertThat(documents).as("scenarios found to check").hasSizeGreaterThan(15);

        documents.forEach((where, yaml) ->
                assertThat(violations(parse(yaml))).as("schema violations in %s", where).isEmpty());
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
                    retry: {maxAttempts: 2}
                """;
        assertThat(violations(parse(valid))).as("the base document is valid").isEmpty();

        Map<String, String> refused = Map.of(
                "an unknown top-level section", valid + "steps: []\n",
                "the with: key the retry guide once named", valid.replace("operationId: read\n",
                        "operationId: read\n    with: {key: one}\n"),
                "dependsOn as a string", valid.replace("operationId: read\n", "operationId: read\n    dependsOn: other\n"),
                "an empty execute", valid.substring(0, valid.indexOf("execute:")) + "execute: []\n",
                "no metadata name", valid.replace("{name: refused}", "{description: none}"),
                "another kind", valid.replace("kind: Scenario", "kind: Catalog"),
                "zero retry attempts", valid.replace("maxAttempts: 2", "maxAttempts: 0"),
                "an unknown step type", valid.replace("operationId: read\n", "operationId: read\n    type: loop\n"),
                "a malformed duration", valid + "timeout: 5 minutes\n");
        refused.forEach((what, yaml) ->
                assertThat(violations(parse(yaml))).as("the schema refuses %s", what).isNotEmpty());
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
