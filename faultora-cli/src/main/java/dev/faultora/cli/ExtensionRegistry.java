package dev.faultora.cli;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.contract.SourceImporter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers the extensions available on the classpath.
 * <p>
 * Assertion providers, report renderers, and source importers advertise
 * themselves through {@code META-INF/services}, so adding one is a matter of
 * putting a module on the classpath rather than editing the composition root.
 * Discovery is limited to those three contracts on purpose:
 * <ul>
 *   <li><b>connectors</b> carry the destination policy that decides which
 *       hosts a run may reach, and</li>
 *   <li><b>fault providers</b> decide what may be broken and where.</li>
 * </ul>
 * Both are constructed explicitly from operator-supplied options, so a jar on
 * the classpath can never widen what a run is allowed to touch. See
 * ADR-004 for the full reasoning.
 * <p>
 * What is discovered is still bounded: an implementation the extension policy
 * does not allow is refused and named, rather than joining the run because it
 * happened to be on the classpath.
 */
final class ExtensionRegistry {

    /** Package the built-in extensions live in. */
    private static final String BUILT_IN_PACKAGE = "dev.faultora.";

    private ExtensionRegistry() {
    }

    /** Assertion providers, keyed by the assertion type they evaluate. */
    static Map<String, AssertionProvider> assertionProviders(ExtensionPolicy policy) {
        Map<String, AssertionProvider> providers = new LinkedHashMap<>();
        for (AssertionProvider provider : ServiceLoader.load(AssertionProvider.class)) {
            if (isAllowed(provider, policy, "assertion provider")) {
                providers.putIfAbsent(provider.type(), provider);
            }
        }
        return providers;
    }

    /** Report renderers, keyed by the {@code --format} value that selects them. */
    static Map<String, ReportRenderer> renderers(ExtensionPolicy policy) {
        Map<String, ReportRenderer> renderers = new LinkedHashMap<>();
        for (ReportRenderer renderer : ServiceLoader.load(ReportRenderer.class)) {
            if (isAllowed(renderer, policy, "report renderer")) {
                renderers.putIfAbsent(renderer.format(), renderer);
            }
        }
        return renderers;
    }

    /**
     * Whether an implementation may take part in the run.
     * <p>
     * Built-ins ship with the release and are reviewed with it. Anything else
     * has to be named by the operator, so that adding a jar to the classpath
     * is not by itself enough to put third-party code in the path of a run.
     * Identity is checked by class name; verifying a digest, and isolating the
     * extension from the run, arrive with the out-of-process plugin protocol.
     */
    private static boolean isAllowed(Object extension, ExtensionPolicy policy, String kind) {
        String className = extension.getClass().getName();
        if (className.startsWith(BUILT_IN_PACKAGE)) {
            return true;
        }
        if (policy != null && policy.allowedExtensions().contains(className)) {
            return true;
        }
        System.err.println("Refused " + kind + " " + className
                + ": it is not a built-in extension and the run does not allow it. "
                + "Pass --allow-extension " + className + " to permit it.");
        return false;
    }

    /**
     * The importer for a source family, or null when none is installed.
     * <p>
     * The CLI selects by family ({@code openapi}) because the exact version of
     * a document — {@code openapi-3.0} or {@code openapi-3.1} — is known only
     * after the importer has read it.
     */
    static SourceImporter importerFor(String sourceFamily, ExtensionPolicy policy) {
        for (SourceImporter importer : ServiceLoader.load(SourceImporter.class)) {
            if (!isAllowed(importer, policy, "source importer")) {
                continue;
            }
            for (String supported : importer.supportedTypes()) {
                if (supported.equals(sourceFamily) || supported.startsWith(sourceFamily + "-")) {
                    return importer;
                }
            }
        }
        return null;
    }
}
