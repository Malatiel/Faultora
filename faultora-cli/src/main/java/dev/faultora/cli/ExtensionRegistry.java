package dev.faultora.cli;

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
 */
final class ExtensionRegistry {

    private ExtensionRegistry() {
    }

    /** Assertion providers, keyed by the assertion type they evaluate. */
    static Map<String, AssertionProvider> assertionProviders() {
        Map<String, AssertionProvider> providers = new LinkedHashMap<>();
        for (AssertionProvider provider : ServiceLoader.load(AssertionProvider.class)) {
            providers.putIfAbsent(provider.type(), provider);
        }
        return providers;
    }

    /** Report renderers, keyed by the {@code --format} value that selects them. */
    static Map<String, ReportRenderer> renderers() {
        Map<String, ReportRenderer> renderers = new LinkedHashMap<>();
        for (ReportRenderer renderer : ServiceLoader.load(ReportRenderer.class)) {
            renderers.putIfAbsent(renderer.format(), renderer);
        }
        return renderers;
    }

    /**
     * The importer for a source family, or null when none is installed.
     * <p>
     * The CLI selects by family ({@code openapi}) because the exact version of
     * a document — {@code openapi-3.0} or {@code openapi-3.1} — is known only
     * after the importer has read it.
     */
    static SourceImporter importerFor(String sourceFamily) {
        for (SourceImporter importer : ServiceLoader.load(SourceImporter.class)) {
            for (String supported : importer.supportedTypes()) {
                if (supported.equals(sourceFamily) || supported.startsWith(sourceFamily + "-")) {
                    return importer;
                }
            }
        }
        return null;
    }
}
