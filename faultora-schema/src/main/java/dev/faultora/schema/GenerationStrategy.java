package dev.faultora.schema;

import java.util.Locale;

/**
 * How generated values relate to the constraints of their schema.
 */
public enum GenerationStrategy {

    /**
     * A payload the schema accepts, with every declared property present.
     * The default: it exercises the widest surface the contract allows.
     */
    VALID,

    /**
     * The smallest payload the schema accepts, with every constrained value
     * sitting on its limit — the values most likely to reveal off-by-one
     * handling in the target.
     */
    BOUNDARY,

    /**
     * A payload violating exactly one constraint, for verifying that the
     * target rejects malformed input rather than accepting it.
     */
    INVALID;

    /** Parse a strategy name, or null when it names none. */
    public static GenerationStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return VALID;
        }
        for (GenerationStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return null;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
