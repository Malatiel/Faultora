package dev.faultora.spec.parser;

import java.util.List;

/**
 * Result of parsing or validation.
 *
 * @param document    the parsed document (null if parsing failed)
 * @param diagnostics diagnostic messages
 */
public record ParseResult<T>(
        T document,
        List<Diagnostic> diagnostics
) {
    public boolean isSuccess() {
        return document != null && diagnostics.stream().noneMatch(Diagnostic::isError);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).toList();
    }
}
