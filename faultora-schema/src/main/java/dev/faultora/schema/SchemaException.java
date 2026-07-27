package dev.faultora.schema;

/**
 * A schema construct that cannot be honoured.
 * <p>
 * Carries the JSON path of the offending part, because a scenario author can
 * only act on a message that names the field: the remedy is to supply that
 * value explicitly instead of generating it.
 */
public class SchemaException extends RuntimeException {

    private final String path;

    public SchemaException(String path, String reason) {
        super((path == null || path.isBlank() ? "schema" : path) + ": " + reason);
        this.path = path;
    }

    /** JSON path of the construct that could not be honoured. */
    public String path() {
        return path;
    }
}
