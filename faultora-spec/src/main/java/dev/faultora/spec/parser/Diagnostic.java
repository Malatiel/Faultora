package dev.faultora.spec.parser;

/**
 * Diagnostic message from parsing or validation.
 * Contains source position information for actionable error messages.
 *
 * @param severity   severity level
 * @param message    human-readable message
 * @param path       path in the document (e.g. "metadata.name")
 * @param line       source line number (-1 if unknown)
 * @param column     source column number (-1 if unknown)
 * @param code       stable category for structural diagnostics
 */
public record Diagnostic(
        Severity severity,
        String message,
        String path,
        int line,
        int column,
        Code code
) {
    public enum Code {
        SCENARIO_INVALID,
        SCENARIO_UNKNOWN_FIELD,
        SCENARIO_TYPE_MISMATCH,
        SCENARIO_WARNING
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public Diagnostic(Severity severity, String message, String path, int line, int column) {
        this(severity, message, path, line, column,
                severity == Severity.ERROR ? Code.SCENARIO_INVALID : Code.SCENARIO_WARNING);
    }

    public static Diagnostic error(String path, String message) {
        return new Diagnostic(Severity.ERROR, message, path, -1, -1);
    }

    public static Diagnostic error(String path, String message, int line, int column) {
        return new Diagnostic(Severity.ERROR, message, path, line, column);
    }

    static Diagnostic error(Code code, String path, String message) {
        return new Diagnostic(Severity.ERROR, message, path, -1, -1, code);
    }

    public static Diagnostic warning(String path, String message) {
        return new Diagnostic(Severity.WARNING, message, path, -1, -1);
    }
}
