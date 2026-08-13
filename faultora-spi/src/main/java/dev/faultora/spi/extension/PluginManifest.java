package dev.faultora.spi.extension;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * What a plugin says about itself, before any of its code runs.
 * <p>
 * An extension was a class name on a classpath, and the only question asked
 * about it was whether an operator typed that name. That is enough to stop a
 * jar joining a run by accident and not enough for anything else: it says
 * nothing about what the plugin was built against, and nothing about whether
 * the bytes are the ones somebody reviewed.
 * <p>
 * <b>Nothing here is trusted on its own.</b> A manifest is written by whoever
 * wrote the plugin; it is a description to check against a policy, not
 * evidence. What makes a plugin the one that was reviewed is the digest of the
 * artifact it arrived in — computed by the loader, and deliberately not a field
 * here, because a file declaring its own hash declares nothing.
 *
 * @param id           what an operator names this plugin by
 * @param version      the plugin's own version, for a diagnostic to quote
 * @param requiresApi  the Faultora versions it was built against
 * @param capabilities what it asks to be allowed to do beyond reading what it
 *                     is handed
 */
public record PluginManifest(
        @JsonProperty("id") String id,
        @JsonProperty("version") String version,
        @JsonProperty("requiresApi") VersionRange requiresApi,
        @JsonProperty("capabilities") Capabilities capabilities
) {
    /** Where a plugin's manifest lives inside its artifact. */
    public static final String LOCATION = "META-INF/faultora-plugin.yaml";

    public PluginManifest {
        capabilities = capabilities == null ? Capabilities.none() : capabilities;
    }

    /**
     * The Faultora versions a plugin says it works with.
     * <p>
     * Compared piecewise rather than as text, so 0.10 does not sort before 0.9,
     * and ignoring anything after the numbers — a plugin built against 0.11
     * works with 0.11-SNAPSHOT, and treating those as different would refuse
     * every plugin on every machine that builds from source.
     *
     * @param from earliest version, inclusive
     * @param to   latest version, inclusive; absent means "and later"
     */
    public record VersionRange(
            @JsonProperty("from") String from,
            @JsonProperty("to") String to) {

        /** Whether a running Faultora is inside this range. */
        public boolean admits(String version) {
            if (version == null) {
                return false;
            }
            if (from != null && compare(version, from) < 0) {
                return false;
            }
            return to == null || compare(version, to) <= 0;
        }

        private static int compare(String left, String right) {
            String[] leftParts = numbersIn(left);
            String[] rightParts = numbersIn(right);
            for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
                int one = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
                int other = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
                if (one != other) {
                    return Integer.compare(one, other);
                }
            }
            return 0;
        }

        private static String[] numbersIn(String version) {
            String numbers = version.split("[^0-9.]", 2)[0];
            while (numbers.endsWith(".")) {
                numbers = numbers.substring(0, numbers.length() - 1);
            }
            return numbers.isEmpty() ? new String[]{"0"} : numbers.split("\\.");
        }

        @Override
        public String toString() {
            return from + (to == null ? " and later" : " to " + to);
        }
    }

    /**
     * What a plugin asks to be allowed to do.
     * <p>
     * Declaring a capability is not being granted one. Until an extension runs
     * somewhere its sockets and its secrets are not the run's, nothing can hold
     * a plugin to what it declared — so a plugin that declares any of these is
     * refused rather than trusted to keep its word. ADR-023 says which slice
     * makes them grantable.
     *
     * @param networkDestinations hosts it intends to contact
     * @param secretHandles       secret handles it intends to resolve
     */
    public record Capabilities(
            @JsonProperty("networkDestinations") Set<String> networkDestinations,
            @JsonProperty("secretHandles") Set<String> secretHandles) {

        public Capabilities {
            networkDestinations = networkDestinations == null
                    ? Set.of() : Set.copyOf(networkDestinations);
            secretHandles = secretHandles == null ? Set.of() : Set.copyOf(secretHandles);
        }

        /** A plugin that is a pure function over what it is handed. */
        public static Capabilities none() {
            return new Capabilities(Set.of(), Set.of());
        }

        /** What this asks for, named for a diagnostic. */
        public java.util.List<String> asked() {
            java.util.List<String> asked = new java.util.ArrayList<>();
            networkDestinations.stream().sorted()
                    .map(host -> "network destination " + host).forEach(asked::add);
            secretHandles.stream().sorted()
                    .map(handle -> "secret handle " + handle).forEach(asked::add);
            return asked;
        }
    }
}
