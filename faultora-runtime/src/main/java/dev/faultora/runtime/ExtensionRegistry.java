package dev.faultora.runtime;

import dev.faultora.assertions.core.DurationAssertionProvider;
import dev.faultora.assertions.core.EventCorrelationAssertionProvider;
import dev.faultora.assertions.core.EventCountAssertionProvider;
import dev.faultora.assertions.core.EventSequenceAssertionProvider;
import dev.faultora.assertions.core.EventUniqueAssertionProvider;
import dev.faultora.assertions.core.HeaderAssertionProvider;
import dev.faultora.assertions.core.JsonPathAssertionProvider;
import dev.faultora.assertions.core.RowBalanceAssertionProvider;
import dev.faultora.assertions.core.RowCountAssertionProvider;
import dev.faultora.assertions.core.RowUniqueAssertionProvider;
import dev.faultora.assertions.core.RowValueAssertionProvider;
import dev.faultora.assertions.core.SchemaAssertionProvider;
import dev.faultora.assertions.core.StatusAssertionProvider;
import dev.faultora.importer.asyncapi.AsyncApiImporter;
import dev.faultora.importer.observations.ObservationImporter;
import dev.faultora.importer.openapi.OpenApiImporter;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.reporting.ConsoleRenderer;
import dev.faultora.reporting.HtmlRenderer;
import dev.faultora.reporting.JsonRenderer;
import dev.faultora.reporting.JUnitXmlRenderer;
import dev.faultora.spi.extension.PluginManifest;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.contract.SourceImporter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;

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
public final class ExtensionRegistry {

    /**
     * The implementations in the trusted runtime bootstrap classpath. Exact
     * class identity includes the defining loader; merged releases additionally
     * require these classes to come from the runtime artifact itself.
     */
    private static final Set<Class<?>> BUILT_INS = Set.of(
            StatusAssertionProvider.class, HeaderAssertionProvider.class,
            JsonPathAssertionProvider.class, DurationAssertionProvider.class,
            SchemaAssertionProvider.class, EventCountAssertionProvider.class,
            EventUniqueAssertionProvider.class, EventCorrelationAssertionProvider.class,
            EventSequenceAssertionProvider.class, RowCountAssertionProvider.class,
            RowBalanceAssertionProvider.class, RowUniqueAssertionProvider.class,
            RowValueAssertionProvider.class,
            ConsoleRenderer.class, JsonRenderer.class, JUnitXmlRenderer.class, HtmlRenderer.class,
            OpenApiImporter.class, AsyncApiImporter.class, ObservationImporter.class);

    private static final String RUNTIME_ORIGIN = originOf(ExtensionRegistry.class);
    private static final RuntimeLayout RUNTIME_LAYOUT = runtimeLayout();

    private ExtensionRegistry() {
    }

    /**
     * What a policy asks for that nothing in this build enforces.
     * <p>
     * Four of {@code ExtensionPolicy}'s five fields are described as controls
     * and read by nobody. Three of them cannot honestly be enforced in-process
     * at all — a memory ceiling, a network allowlist and a set of permitted
     * secret handles all assume the extension is somewhere its heap, its
     * sockets and its {@code SecretResolver} are not shared with the run — so
     * they arrive with the out-of-process protocol or not at all. Implementing
     * them in-process as approximations would be a control that reports success
     * and prevents nothing.
     * <p>
     * Until then, asking is refused rather than ignored, and each entry
     * disappears from here as its enforcement lands.
     *
     * @return what was asked and is not enforced, empty when there is nothing
     */
    public static List<String> notYetEnforced(ExtensionPolicy policy) {
        if (policy == null) {
            return List.of();
        }
        List<String> requests = new ArrayList<>();
        if (policy.requireProcessIsolation()) {
            requests.add("process isolation for extensions");
        }
        if (policy.maxResourceMemoryMb() > 0) {
            requests.add("a memory ceiling of " + policy.maxResourceMemoryMb()
                    + "MB per extension");
        }
        if (!policy.maxNetworkDestinations().isEmpty()) {
            requests.add("a network allowlist for extensions");
        }
        if (!policy.secretCapabilities().isEmpty()) {
            requests.add("a secret allowlist for extensions");
        }
        return requests;
    }

    /** Stop before a run starts when the policy describes something imaginary. */
    static void refuseWhatIsNotEnforced(ExtensionPolicy policy) {
        requireSupportedRuntime();
        List<String> requests = notYetEnforced(policy);
        if (!requests.isEmpty()) {
            throw new UnenforceablePolicy(requests);
        }
    }

    /** Assertion providers, keyed by the assertion type they evaluate. */
    public static Map<String, AssertionProvider> assertionProviders(ExtensionPolicy policy) {
        requireSupportedRuntime();
        Map<String, AssertionProvider> providers = new LinkedHashMap<>();
        ServiceLoader.load(AssertionProvider.class).stream()
                .filter(candidate -> isAllowed(candidate.type(), policy, "assertion provider"))
                .map(ServiceLoader.Provider::get)
                .forEach(provider -> providers.putIfAbsent(provider.type(), provider));
        return providers;
    }

    /** Report renderers, keyed by the {@code --format} value that selects them. */
    public static Map<String, ReportRenderer> renderers(ExtensionPolicy policy) {
        requireSupportedRuntime();
        Map<String, ReportRenderer> renderers = new LinkedHashMap<>();
        ServiceLoader.load(ReportRenderer.class).stream()
                .filter(candidate -> isAllowed(candidate.type(), policy, "report renderer"))
                .map(ServiceLoader.Provider::get)
                .forEach(renderer -> renderers.putIfAbsent(renderer.format(), renderer));
        return renderers;
    }

    /**
     * Whether an implementation may take part in the run, checked before
     * calling its constructor or initializing its class.
     * <p>
     * Built-ins ship with the release and are reviewed with it. Anything else
     * has to answer three questions, and a plugin that cannot answer one of
     * them is refused with which:
     * <ol>
     *   <li><b>Which artifact is this?</b> Named by the operator as a class
     *       name or as the digest of the jar it arrived in. A class name is
     *       the weaker of the two — two jars can offer the same class, one
     *       reviewed and one not — so the refusal quotes the digest, which is
     *       what an operator would have to paste to permit exactly these
     *       bytes.</li>
     *   <li><b>What was it built against?</b> Its manifest declares a range,
     *       and a plugin outside it is refused here rather than throwing a
     *       {@code NoSuchMethodError} halfway through somebody's run.</li>
     *   <li><b>What does it want?</b> A plugin declaring a capability is
     *       refused, because nothing can hold it to the declaration while it
     *       shares this JVM's sockets and secrets. ADR-023 says which slice
     *       makes them grantable.</li>
     * </ol>
     * A non-built-in without a manifest is refused. It cannot say what it was
     * built against, and asking is the whole point of having one.
     */
    private static boolean isAllowed(Class<?> extensionType, ExtensionPolicy policy, String kind) {
        String className = extensionType.getName();
        if (isBuiltIn(extensionType)) {
            return true;
        }
        PluginArtifact.Inspection inspection = PluginArtifact.inspect(extensionType);
        if (inspection.refusal() != null) {
            refuse(kind, className, inspection.refusal());
            return false;
        }
        PluginArtifact artifact = inspection.artifact();
        Set<String> permitted = policy == null ? Set.of() : policy.allowedExtensions();

        if (!permitted.contains(className)
                && (artifact.digest() == null || !permitted.contains(artifact.digest()))) {
            refuse(kind, className, "the run does not allow it. Pass "
                    + "--allow-extension " + (artifact.isIdentifiable()
                            ? artifact.digest() + " to permit exactly these bytes, or "
                                    + className + " to permit any jar offering that class"
                            : className));
            return false;
        }

        PluginManifest manifest = artifact.manifest();
        if (manifest == null) {
            refuse(kind, className, "it carries no " + PluginManifest.LOCATION
                    + ", so it cannot say what it was built against");
            return false;
        }
        if (manifest.requiresApi() != null && !manifest.requiresApi().admits(runningVersion())) {
            refuse(kind, className, manifest.id() + " " + manifest.version()
                    + " was built for Faultora " + manifest.requiresApi()
                    + ", and this is " + runningVersion());
            return false;
        }
        if (!manifest.capabilities().asked().isEmpty()) {
            refuse(kind, className, "it asks for "
                    + String.join(", ", manifest.capabilities().asked())
                    + ", and nothing here can hold it to that while it shares this "
                    + "process. See ADR-023");
            return false;
        }
        return true;
    }

    private static boolean isBuiltIn(Class<?> extensionType) {
        String origin = originOf(extensionType);
        return BUILT_INS.contains(extensionType) && origin != null && RUNTIME_ORIGIN != null
                && (!RUNTIME_LAYOUT.merged() || RUNTIME_ORIGIN.equals(origin));
    }

    /** Inspect our own artifact; a copied SPI class cannot choose this trust mode. */
    private static RuntimeLayout runtimeLayout() {
        if (RUNTIME_ORIGIN == null) {
            return new RuntimeLayout(false, "Cannot verify the runtime artifact's origin");
        }
        try {
            URI origin = URI.create(RUNTIME_ORIGIN);
            if (!"file".equals(origin.getScheme())) {
                return new RuntimeLayout(false, "Cannot verify the runtime artifact's origin");
            }
            Path artifact = Path.of(origin);
            String spiClass = "dev/faultora/spi/contract/AssertionProvider.class";
            if (Files.isDirectory(artifact)) {
                return new RuntimeLayout(Files.isRegularFile(artifact.resolve(spiClass)), null);
            }
            try (JarFile shipped = new JarFile(artifact.toFile())) {
                return new RuntimeLayout(shipped.getJarEntry(spiClass) != null, null);
            }
        } catch (IOException | IllegalArgumentException | SecurityException unreadable) {
            return new RuntimeLayout(false, "Cannot verify the runtime artifact's contents");
        }
    }

    private static void requireSupportedRuntime() {
        if (RUNTIME_LAYOUT.refusal() != null) {
            throw new IllegalStateException(RUNTIME_LAYOUT.refusal());
        }
    }

    private record RuntimeLayout(boolean merged, String refusal) {
    }

    /** A URL string comparison avoids DNS resolution while checking origins. */
    private static String originOf(Class<?> type) {
        try {
            var domain = type.getProtectionDomain();
            var source = domain == null ? null : domain.getCodeSource();
            return source == null || source.getLocation() == null
                    ? null : source.getLocation().toExternalForm();
        } catch (SecurityException unavailable) {
            return null;
        }
    }

    private static void refuse(String kind, String className, String because) {
        System.err.println("Refused " + kind + " " + className + ": " + because + ".");
    }

    /**
     * The Faultora a plugin is being asked to work with.
     * <p>
     * From a resource Maven filters, rather than a constant in source: the
     * version a compatibility range is compared against has to be the version
     * this actually is, and a constant would be a second place to remember on
     * every release. A build from a jar has an implementation version too, and
     * it is preferred — it is what the artifact was stamped with.
     */
    static String runningVersion() {
        return FaultoraVersion.current();
    }

    /**
     * The importer for a source family, or null when none is installed.
     * <p>
     * The CLI selects by family ({@code openapi}) because the exact version of
     * a document — {@code openapi-3.0} or {@code openapi-3.1} — is known only
     * after the importer has read it.
     */
    public static SourceImporter importerFor(String sourceFamily, ExtensionPolicy policy) {
        requireSupportedRuntime();
        return ServiceLoader.load(SourceImporter.class).stream()
                .filter(candidate -> isAllowed(candidate.type(), policy, "source importer"))
                .map(ServiceLoader.Provider::get)
                .filter(importer -> importer.supportedTypes().stream()
                        .anyMatch(supported -> supported.equals(sourceFamily)
                                || supported.startsWith(sourceFamily + "-")))
                .findFirst().orElse(null);
    }

    /** Installed importers admitted by the same checks used when importing. */
    public static List<SourceImporter> importers(ExtensionPolicy policy) {
        requireSupportedRuntime();
        return ServiceLoader.load(SourceImporter.class).stream()
                .filter(candidate -> isAllowed(candidate.type(), policy, "source importer"))
                .map(ServiceLoader.Provider::get).toList();
    }
}
