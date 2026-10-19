package dev.faultora.runtime;

/**
 * The version of this Faultora build, from the one place it is set.
 * <p>
 * A packaged artifact carries an implementation version, and it is preferred:
 * it is what the jar was stamped with. A build running from classes reads the
 * resource Maven filters from the project version, so no source file has to
 * name a release and none can fall behind one.
 */
public final class FaultoraVersion {

    private static final String BUILT = built();

    private FaultoraVersion() {
    }

    /** The running version, or {@code "0"} when neither source can be read. */
    public static String current() {
        String packaged = FaultoraVersion.class.getPackage().getImplementationVersion();
        return packaged != null ? packaged : BUILT;
    }

    private static String built() {
        try (var declared = FaultoraVersion.class.getResourceAsStream("/faultora-version.properties")) {
            if (declared == null) {
                return "0";
            }
            var properties = new java.util.Properties();
            properties.load(declared);
            return properties.getProperty("version", "0");
        } catch (java.io.IOException unreadable) {
            return "0";
        }
    }
}
