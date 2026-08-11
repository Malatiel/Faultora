package dev.faultora.spec.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which scenario API versions this release reads, and for how long.
 * <p>
 * One place, because the version a document declares, the version {@code init}
 * writes, the version the migrator produces and the version the documentation
 * promises are the same fact four times. Any two of them stated separately
 * agree on the day they are written.
 * <p>
 * <b>{@link #CURRENT} is frozen.</b> From 1.0 the fields a {@code v1} document
 * may carry do not change except by addition, and an addition that a previous
 * 1.x release would have refused is a new version rather than a new field.
 * {@code ScenarioSurfaceTest} is what makes that a rule rather than a promise:
 * it holds the accepted surface as a committed list and fails when the parser
 * starts accepting something that is not on it.
 * <p>
 * {@link #DEPRECATED} versions still parse. Refusing them on the release whose
 * purpose is stability would break every scenario written against the preview,
 * which is the opposite of what freezing is for — so they are read, warned
 * about, and the warning says when reading them will stop.
 */
public final class ApiVersions {

    /** The frozen version. What {@code init} writes and the migrator produces. */
    public static final String CURRENT = "faultora.dev/v1";

    /** Read, with a warning, until the release named in {@link #SUNSET}. */
    public static final Set<String> DEPRECATED = Set.of("faultora.dev/v1alpha1");

    /**
     * The release that stops reading {@link #DEPRECATED} versions.
     * <p>
     * Named rather than "a future release", because a deprecation without a
     * date is a warning nobody can act on and everybody learns to scroll past.
     */
    public static final String SUNSET = "2.0";

    private ApiVersions() {
    }

    /** Every version a document may declare here. */
    public static Set<String> accepted() {
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add(CURRENT);
        accepted.addAll(DEPRECATED);
        return accepted;
    }

    /** Whether a document declaring this version can be read at all. */
    public static boolean isAccepted(String apiVersion) {
        return CURRENT.equals(apiVersion) || DEPRECATED.contains(apiVersion);
    }

    /** Whether reading this version is on borrowed time. */
    public static boolean isDeprecated(String apiVersion) {
        return DEPRECATED.contains(apiVersion);
    }

    /**
     * What to tell somebody whose document declares a version on the way out.
     * <p>
     * Says what to do and by when. A diagnostic that only names the problem
     * leaves the reader to find the answer, and this one has an answer:
     * {@code faultora migrate} writes it.
     */
    public static String deprecationNotice(String apiVersion) {
        return apiVersion + " is deprecated and will not be read from " + SUNSET
                + ". Run `faultora migrate --scenario <file>` to move it to "
                + CURRENT + "; nothing else in the document has to change.";
    }

    /** What to tell somebody whose document declares something unreadable. */
    public static String unsupportedNotice(String apiVersion) {
        return "Unsupported apiVersion: " + apiVersion
                + ". This release reads " + String.join(" and ", accepted()) + ".";
    }
}
