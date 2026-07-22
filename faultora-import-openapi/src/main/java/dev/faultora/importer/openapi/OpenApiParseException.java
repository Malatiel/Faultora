package dev.faultora.importer.openapi;

/**
 * Thrown when an OpenAPI document cannot be parsed.
 */
public class OpenApiParseException extends RuntimeException {

    public OpenApiParseException(String message) {
        super(message);
    }

    public OpenApiParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
