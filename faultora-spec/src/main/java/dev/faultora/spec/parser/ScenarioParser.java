package dev.faultora.spec.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.spec.model.ScenarioDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses YAML scenario documents into the typed model.
 * Produces source-positioned diagnostics for actionable error messages.
 */
public class ScenarioParser {

    private final ObjectMapper yamlMapper;

    public ScenarioParser() {
        this.yamlMapper = new ObjectMapper(YAMLFactory.builder().build());
    }

    /**
     * Parse a YAML scenario document.
     *
     * @param content the YAML content
     * @return parse result with document or diagnostics
     */
    public ParseResult<ScenarioDocument> parse(String content) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (content == null || content.isBlank()) {
            diagnostics.add(Diagnostic.error("", "Scenario document is empty"));
            return new ParseResult<>(null, diagnostics);
        }

        try {
            JsonNode root = yamlMapper.readTree(content);

            // Validate apiVersion
            String apiVersion = getTextOrNull(root, "apiVersion");
            if (apiVersion == null) {
                diagnostics.add(Diagnostic.error("apiVersion", "apiVersion is required"));
            } else if (!ScenarioDocument.SUPPORTED_API_VERSION.equals(apiVersion)) {
                diagnostics.add(Diagnostic.error("apiVersion",
                        "Unsupported apiVersion: " + apiVersion +
                        ". Expected: " + ScenarioDocument.SUPPORTED_API_VERSION));
            }

            // Validate kind
            String kind = getTextOrNull(root, "kind");
            if (kind == null) {
                diagnostics.add(Diagnostic.error("kind", "kind is required"));
            } else if (!ScenarioDocument.EXPECTED_KIND.equals(kind)) {
                diagnostics.add(Diagnostic.error("kind",
                        "Unsupported kind: " + kind + ". Expected: " + ScenarioDocument.EXPECTED_KIND));
            }

            // Validate metadata
            JsonNode metadata = root.get("metadata");
            if (metadata == null || !metadata.isObject()) {
                diagnostics.add(Diagnostic.error("metadata", "metadata is required and must be an object"));
            } else {
                String name = getTextOrNull(metadata, "name");
                if (name == null || name.isBlank()) {
                    diagnostics.add(Diagnostic.error("metadata.name", "Scenario name is required"));
                }
            }

            // Check for unknown top-level fields
            List<String> knownFields = List.of("apiVersion", "kind", "metadata", "inputs",
                    "setup", "execute", "faults", "assertions", "cleanup");
            root.fieldNames().forEachRemaining(field -> {
                if (!knownFields.contains(field)) {
                    diagnostics.add(Diagnostic.warning("", "Unknown top-level field: " + field));
                }
            });

            if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
                return new ParseResult<>(null, diagnostics);
            }

            // Deserialize
            ScenarioDocument document = yamlMapper.treeToValue(root, ScenarioDocument.class);
            return new ParseResult<>(document, diagnostics);

        } catch (UnrecognizedPropertyException e) {
            diagnostics.add(Diagnostic.error(e.getPath().get(0).getFieldName(),
                    "Unrecognized field: " + e.getPropertyName()));
            return new ParseResult<>(null, diagnostics);
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error("", "Failed to parse YAML: " + e.getMessage()));
            return new ParseResult<>(null, diagnostics);
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
