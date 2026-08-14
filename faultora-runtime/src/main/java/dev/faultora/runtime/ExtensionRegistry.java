package dev.faultora.runtime;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spi.extension.PluginManifest;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.contract.SourceImporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

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

    /** Package the built-in extensions live in. */
    private static final String BUILT_IN_PACKAGE = "dev.faultora.";

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
        List<String> requests = notYetEnforced(policy);
        if (!requests.isEmpty()) {
            throw new UnenforceablePolicy(requests);
        }
    }

    /** Assertion providers, keyed by the assertion type they evaluate. */
    public static Map<String, AssertionProvider> assertionProviders(ExtensionPolicy policy) {
        Map<String, AssertionProvider> providers = new LinkedHashMap<>();
        for (AssertionProvider provider : ServiceLoader.load(AssertionProvider.class)) {
            if (isAllowed(provider, policy, "assertion provider")) {
                providers.putIfAbsent(provider.type(), provider);
            }
        }
        return providers;
    }

    /** Report renderers, keyed by the {@code --format} value that selects them. */
    public static Map<String, ReportRenderer> renderers(ExtensionPolicy policy) {
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
    private static boolean isAllowed(Object extension, ExtensionPolicy policy, String kind) {
        String className = extension.getClass().getName();
        if (className.startsWith(BUILT_IN_PACKAGE)) {
            return true;
        }
        PluginArtifact artifact = PluginArtifact.of(extension);
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
        String packaged = ExtensionRegistry.class.getPackage().getImplementationVersion();
        return packaged != null ? packaged : BUILT_VERSION;
    }

    /** What this build is, read once from what Maven filtered in. */
    private static final String BUILT_VERSION = builtVersion();

    private static String builtVersion() {
        try (var declared = ExtensionRegistry.class
                .getResourceAsStream("/faultora-version.properties")) {
            if (declared == null) {
                return "0";
            }
            var properties = new java.util.Properties();
            properties.load(declared);
            return properties.getProperty("version", "0");
        } catch (Exception unreadable) {
            return "0";
        }
    }

    /**
     * The importer for a source family, or null when none is installed.
     * <p>
     * The CLI selects by family ({@code openapi}) because the exact version of
     * a document — {@code openapi-3.0} or {@code openapi-3.1} — is known only
     * after the importer has read it.
     */
    public static SourceImporter importerFor(String sourceFamily, ExtensionPolicy policy) {
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
