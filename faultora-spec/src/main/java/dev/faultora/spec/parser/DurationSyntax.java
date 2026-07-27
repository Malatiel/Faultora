package dev.faultora.spec.parser;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * The single definition of the scenario duration grammar.
 * <p>
 * A duration is a whole number of milliseconds, optionally suffixed with
 * {@code ms}, {@code s}, or {@code m}. Validation and plan compilation both
 * resolve durations through this class, so a value accepted by {@code validate}
 * is never rejected — or read differently — by the compiler.
 */
public final class DurationSyntax {

    /** Human-readable description of accepted forms, for diagnostics. */
    public static final String ACCEPTED_FORMS = "milliseconds, ms, s, or m";

    private DurationSyntax() {
    }

    /**
     * Whether a duration field was left out. An absent duration is not a
     * malformed one: callers decide whether it is required.
     */
    public static boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Parse a duration to milliseconds.
     *
     * @param value the declared duration
     * @return the duration in milliseconds, or empty when the value is absent,
     *         malformed, or overflows
     */
    public static OptionalLong parseMillis(String value) {
        if (isAbsent(value)) {
            return OptionalLong.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (normalized.endsWith("ms")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 1000;
        } else if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 60_000;
        }
        try {
            return OptionalLong.of(Math.multiplyExact(Long.parseLong(normalized), multiplier));
        } catch (ArithmeticException | NumberFormatException malformed) {
            return OptionalLong.empty();
        }
    }

    /** Whether the value is a well-formed duration greater than zero. */
    public static boolean isPositive(String value) {
        OptionalLong parsed = parseMillis(value);
        return parsed.isPresent() && parsed.getAsLong() > 0;
    }
}
