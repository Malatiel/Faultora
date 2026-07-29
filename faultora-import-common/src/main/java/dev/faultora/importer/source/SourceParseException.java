package dev.faultora.importer.source;

/**
 * Thrown when a source document cannot be read as the document it claims to be.
 * <p>
 * Importers turn this into an import diagnostic rather than letting it escape:
 * a malformed description is a configuration error the author can fix, not a
 * failure of the run.
 */
public class SourceParseException extends RuntimeException {

    public SourceParseException(String message) {
        super(message);
    }

    public SourceParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
