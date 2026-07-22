package dev.faultora.spec.parser;

import java.util.List;

/**
 * Diagnostic message from parsing or validation.
 * Contains source position information for actionable error messages.
 *
 * @param severity   severity level
 * @param message    human-readable message
 * @param path       path in the document (e.g. "metadata.name")
 * @param line       source line number (-1 if unknown)
 * @param column     source column number (-1 if unknown)
 */
public record Diagnostic(
        Severity severity,
        String message,
        String path,
        int line,
        int column
) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public static Diagnostic error(String path, String message) {
        return new Diagnostic(Severity.ERROR, message, path, -1, -1);
    }

    public static Diagnostic error(String path, String message, int line, int column) {
        return new Diagnostic(Severity.ERROR, message, path, line, column);
    }

    public static Diagnostic warning(String path, String message) {
        return new Diagnostic(Severity.WARNING, message, path, -1, -1);
    }
}
