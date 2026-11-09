package dev.faultora.spec.parser;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.spec.model.ApiVersions;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spec.model.ScenarioStep;
import dev.faultora.spec.model.AssertionStep;
import dev.faultora.spec.model.FaultStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Parses YAML scenario documents into the typed model.
 * Produces source-positioned diagnostics for actionable error messages.
 */
public class ScenarioParser {

    private static final Set<Class<?>> PATH_RECORDS = Set.of(
            ScenarioDocument.class, ScenarioMetadata.class, ScenarioStep.class,
            ScenarioStep.RetryPolicy.class, ScenarioStep.Condition.class, ScenarioStep.Generate.class,
            AssertionStep.class, FaultStep.class);
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
            if (root == null || !root.isObject()) {
                diagnostics.add(Diagnostic.error("", "Scenario document must be an object"));
                return new ParseResult<>(null, diagnostics);
            }

            // Validate apiVersion
            String apiVersion = getTextOrNull(root, "apiVersion");
            if (apiVersion == null) {
                diagnostics.add(Diagnostic.error("apiVersion", "apiVersion is required"));
            } else if (!ApiVersions.isAccepted(apiVersion)) {
                diagnostics.add(Diagnostic.error("apiVersion",
                        ApiVersions.unsupportedNotice(apiVersion)));
            } else if (ApiVersions.isDeprecated(apiVersion)) {
                // A warning, never an error. A document written against the
                // preview still runs, and the release whose point is stability
                // must not be the one that turns every existing pipeline red.
                diagnostics.add(Diagnostic.warning("apiVersion",
                        ApiVersions.deprecationNotice(apiVersion)));
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
                    "setup", "execute", "faults", "assertions", "cleanup", "timeout");
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
            diagnostics.add(Diagnostic.error(Diagnostic.Code.SCENARIO_UNKNOWN_FIELD, structuralPath(e),
                    "Unrecognized field: " + e.getPropertyName()));
            return new ParseResult<>(null, diagnostics);
        } catch (JsonMappingException e) {
            diagnostics.add(Diagnostic.error(Diagnostic.Code.SCENARIO_TYPE_MISMATCH, structuralPath(e),
                    "Failed to parse YAML: " + e.getMessage()));
            return new ParseResult<>(null, diagnostics);
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error("", "Failed to parse YAML: " + e.getMessage()));
            return new ParseResult<>(null, diagnostics);
        }
    }

    /** Only declared record fields and list positions can appear in exported diagnostics. */
    private static String structuralPath(JsonMappingException error) {
        StringBuilder path = new StringBuilder();
        for (var reference : error.getPath()) {
            Object from = reference.getFrom();
            if (from == null) {
                break;
            }
            Class<?> owner = from instanceof Class<?> type ? type : from.getClass();
            if (reference.getIndex() >= 0 && List.class.isAssignableFrom(owner)) {
                path.append('[').append(reference.getIndex()).append(']');
                continue;
            }
            String field = reference.getFieldName();
            if (!PATH_RECORDS.contains(owner) || Arrays.stream(owner.getRecordComponents())
                    .noneMatch(component -> component.getName().equals(field))) {
                // Unknown fields and keys of input/parameter maps are document data.
                break;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(field);
        }
        return path.toString();
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
