package dev.faultora.importer.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.model.security.ContentDigest;

/**
 * A source document an importer was handed, and the ways every importer needs
 * to read it.
 * <p>
 * OpenAPI and AsyncAPI are different vocabularies over the same substrate: a
 * JSON or YAML document whose parts refer to each other by JSON pointer. That
 * substrate is here so each importer only has to know its own vocabulary.
 * <p>
 * References are resolved <em>within the document</em> and nowhere else. An
 * importer that followed a reference to another host would make importing a
 * description a network operation, and a description is exactly the kind of
 * file that arrives from somewhere else.
 */
public final class SourceDocument {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder().build());

    /** How many references may be followed before a chain is called a loop. */
    private static final int MAX_REFERENCE_DEPTH = 32;

    private SourceDocument() {
    }

    /**
     * Parse a document that may be JSON or YAML.
     *
     * @throws SourceParseException when it is neither
     */
    public static JsonNode parse(String content) {
        if (content == null || content.isBlank()) {
            throw new SourceParseException("Document content is empty");
        }
        String trimmed = content.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readTree(trimmed);
            } catch (Exception notJson) {
                throw new SourceParseException(
                        "Failed to parse as JSON: " + notJson.getMessage(), notJson);
            }
        }
        try {
            return YAML.readTree(trimmed);
        } catch (Exception notYaml) {
            throw new SourceParseException(
                    "Failed to parse as YAML: " + notYaml.getMessage(), notYaml);
        }
    }

    /** The digest that identifies the version of a catalog imported from this. */
    public static String digest(String content) {
        return ContentDigest.sha256Uri(content);
    }

    /** A text field, or null when it is absent or is not text. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** An object field, or null when it is absent or is not an object. */
    public static JsonNode object(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    /**
     * Follow a node's {@code $ref} until it points at something real.
     * <p>
     * A node that is not a reference is returned unchanged, so callers can
     * resolve unconditionally rather than testing first.
     *
     * @throws SourceParseException when the reference leaves the document,
     *                              points nowhere, or loops
     */
    public static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
            String reference = text(current, "$ref");
            if (reference == null) {
                return current;
            }
            if (!reference.startsWith("#/")) {
                throw new SourceParseException(
                        "Reference '" + reference + "' points outside this document, "
                                + "which importing never follows");
            }
            JsonNode target = pointer(root, reference.substring(1));
            if (target == null) {
                throw new SourceParseException(
                        "Reference '" + reference + "' points at nothing in this document");
            }
            current = target;
        }
        throw new SourceParseException(
                "Reference chain starting at '" + text(node, "$ref") + "' does not end");
    }

    /** The JSON pointer a reference names, or null when it names nothing. */
    private static JsonNode pointer(JsonNode root, String pointer) {
        JsonNode found = root.at(pointer);
        return found.isMissingNode() ? null : found;
    }

    /**
     * The last segment of a reference, which is the name the document gave the
     * thing it points at.
     *
     * @return the name, or null when the node is not a reference
     */
    public static String referencedName(JsonNode node) {
        String reference = text(node, "$ref");
        if (reference == null) {
            return null;
        }
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash < 0 ? reference : reference.substring(lastSlash + 1);
    }
}
