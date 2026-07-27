package dev.faultora.importer.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import dev.faultora.model.security.ContentDigest;

/**
 * Utility for parsing OpenAPI documents and computing content digests.
 * Supports both JSON and YAML formats.
 */
public final class OpenApiUtils {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(YAMLFactory.builder().build());

    private OpenApiUtils() {}

    /**
     * Parse an OpenAPI document string (JSON or YAML) into a JsonNode tree.
     *
     * @param content the raw document content
     * @return the parsed JsonNode
     * @throws OpenApiParseException if the content cannot be parsed
     */
    public static JsonNode parseDocument(String content) {
        if (content == null || content.isBlank()) {
            throw new OpenApiParseException("Document content is empty");
        }

        String trimmed = content.strip();

        // Try JSON first (starts with { or [)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON_MAPPER.readTree(trimmed);
            } catch (Exception e) {
                throw new OpenApiParseException("Failed to parse as JSON: " + e.getMessage(), e);
            }
        }

        // Try YAML
        try {
            return YAML_MAPPER.readTree(trimmed);
        } catch (Exception e) {
            throw new OpenApiParseException("Failed to parse as YAML: " + e.getMessage(), e);
        }
    }

    /**
     * Compute a SHA-256 content digest for caching.
     *
     * @param content the raw document content
     * @return hex-encoded SHA-256 digest
     */
    public static String computeDigest(String content) {
        return ContentDigest.sha256Uri(content);
    }

    /**
     * Extract the OpenAPI version from a parsed document.
     *
     * @param root the parsed document root
     * @return the version string (e.g. "3.0.3", "3.1.0"), or null if not found
     */
    public static String extractVersion(JsonNode root) {
        if (root == null) return null;
        // OpenAPI 3.x uses "openapi" field
        JsonNode openapi = root.get("openapi");
        if (openapi != null && openapi.isTextual()) {
            return openapi.asText();
        }
        return null;
    }

    /**
     * Check if the document is an OpenAPI 3.x document.
     */
    public static boolean isOpenApi3(JsonNode root) {
        String version = extractVersion(root);
        return version != null && version.startsWith("3.");
    }

    /**
     * Get a text field value, returning null if absent or non-textual.
     */
    public static String getText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    /**
     * Get an object field, returning null if absent or not an object.
     */
    public static JsonNode getObject(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        return (child != null && child.isObject()) ? child : null;
    }
}
