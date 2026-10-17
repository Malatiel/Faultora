package dev.faultora.testkit;

/** Mandatory prerequisites in qualification, explicit optional skips in a local build. */
public final class Qualification {
    public static final String STRICT_PROPERTY = "faultora.qualification.strict";

    private Qualification() { }

    public static boolean available(boolean available, String prerequisite) {
        return available(available, prerequisite, Boolean.getBoolean(STRICT_PROPERTY));
    }

    static boolean available(boolean available, String prerequisite, boolean strict) {
        if (!available) {
            String reason = "Qualification prerequisite unavailable: " + prerequisite;
            if (strict) {
                throw new IllegalStateException(reason);
            }
            System.err.println(reason + "; optional local tests skipped");
        }
        return available;
    }
}
