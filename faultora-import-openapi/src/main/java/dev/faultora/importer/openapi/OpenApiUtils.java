package dev.faultora.importer.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.importer.source.SourceDocument;
import dev.faultora.importer.source.SourceParseException;

/**
 * Reading an OpenAPI document.
 * <p>
 * Everything here that is not about OpenAPI itself — parsing JSON or YAML,
 * digesting, reading fields — belongs to {@link SourceDocument}, which the
 * AsyncAPI importer reads its documents with too.
 */
public final class OpenApiUtils {

    private OpenApiUtils() {}

    /**
     * Parse an OpenAPI document string (JSON or YAML) into a JsonNode tree.
     *
     * @throws OpenApiParseException if the content cannot be parsed
     */
    public static JsonNode parseDocument(String content) {
        try {
            return SourceDocument.parse(content);
        } catch (SourceParseException unreadable) {
            throw new OpenApiParseException(unreadable.getMessage(), unreadable);
        }
    }

    /** Compute a SHA-256 content digest for caching. */
    public static String computeDigest(String content) {
        return SourceDocument.digest(content);
    }

    /**
     * Extract the OpenAPI version from a parsed document.
     *
     * @return the version string (e.g. "3.0.3", "3.1.0"), or null if not found
     */
    public static String extractVersion(JsonNode root) {
        return SourceDocument.text(root, "openapi");
    }

    /** Check if the document is an OpenAPI 3.x document. */
    public static boolean isOpenApi3(JsonNode root) {
        String version = extractVersion(root);
        return version != null && version.startsWith("3.");
    }

    /** Get a text field value, returning null if absent or non-textual. */
    public static String getText(JsonNode node, String field) {
        return SourceDocument.text(node, field);
    }

    /** Get an object field, returning null if absent or not an object. */
    public static JsonNode getObject(JsonNode node, String field) {
        return SourceDocument.object(node, field);
    }
}
