package dev.faultora.runtime;

import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spi.contract.AssertionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which plugin is this, and may it run?
 * <p>
 * A class name is not an identity: two jars can offer the same class, one
 * reviewed and one not, and an operator who named the class has said nothing
 * about which of them they meant. So the tests here build real jars — one
 * reviewed, one not — with the same class inside, and ask the registry to tell
 * them apart.
 * <p>
 * Every case has both halves. A permitted plugin loads, and the same plugin
 * with one byte changed does not; a plugin built for this release loads, and
 * one built for another does not.
 */
class PluginIdentityTest {

    /** A tiny assertion provider, as source, so a jar can be built around it. */
    private static final String PROVIDER_SOURCE = """
            package somebody.elses;
            import dev.faultora.spi.contract.AssertionProvider;
            import dev.faultora.spi.context.AssertionContext;
            import dev.faultora.spi.context.EvidenceView;
            import dev.faultora.spi.result.AssertionResult;
            import java.util.Map;
            public class OutsideProvider implements AssertionProvider {
                public String type() { return "outside"; }
                public AssertionResult evaluate(String assertionType,
                        Map<String, Object> params, EvidenceView evidence,
                        AssertionContext context) {
                    return AssertionResult.pass("from outside");
                }
            }
            """;

    private static final String CLASS_NAME = "somebody.elses.OutsideProvider";

    @TempDir
    Path directory;

    /**
     * Build a jar carrying the provider, its service declaration and a
     * manifest.
     *
     * @param manifest what the plugin declares, or null to carry none
     */
    private Path jarDeclaring(String name, String manifest) throws Exception {
        Path classes = Files.createDirectories(directory.resolve(name + "-classes"));
        Path source = classes.resolve("OutsideProvider.java");
        Files.writeString(source, PROVIDER_SOURCE);

        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream complaints = new ByteArrayOutputStream();
        int compiled = compiler.run(null, null, complaints,
                "-cp", spiClasspath(),
                "-d", classes.toString(), source.toString());
        assertThat(compiled)
                .as(() -> "the fixture compiles: " + complaints)
                .isZero();

        Path jar = directory.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            write(out, "somebody/elses/OutsideProvider.class",
                    Files.readAllBytes(classes.resolve("somebody/elses/OutsideProvider.class")));
            write(out, "META-INF/services/dev.faultora.spi.contract.AssertionProvider",
                    CLASS_NAME.getBytes(StandardCharsets.UTF_8));
            if (manifest != null) {
                write(out, "META-INF/faultora-plugin.yaml",
                        manifest.getBytes(StandardCharsets.UTF_8));
            }
        }
        return jar;
    }

    /**
     * Where the SPI actually lives, rather than whatever the test runner put
     * in {@code java.class.path}.
     * <p>
     * Surefire hands a forked JVM a manifest-only jar, and javac does not
     * follow its {@code Class-Path} — so asking the classes themselves where
     * they came from is the only reliable answer.
     */
    private static String spiClasspath() {
        return java.util.stream.Stream.of(
                        AssertionProvider.class,
                        dev.faultora.spi.context.AssertionContext.class,
                        dev.faultora.spi.context.EvidenceView.class,
                        dev.faultora.spi.result.AssertionResult.class)
                .map(type -> type.getProtectionDomain().getCodeSource().getLocation())
                .map(url -> {
                    try {
                        return Path.of(url.toURI()).toString();
                    } catch (Exception notAFile) {
                        throw new IllegalStateException(notAFile);
                    }
                })
                .distinct()
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static void write(JarOutputStream out, String name, byte[] content)
            throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static String manifestFor(String range) {
        return """
                id: outside-assertions
                version: 1.4.0
                requiresApi:
                  from: %s
                """.formatted(range);
    }

    /** What the registry decides about a plugin in this jar, under this policy. */
    private Decision decide(Path jar, Set<String> allowed) throws Exception {
        ByteArrayOutputStream refusals = new ByteArrayOutputStream();
        PrintStream wasErr = System.err;
        System.setErr(new PrintStream(refusals, true, StandardCharsets.UTF_8));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader was = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                // Loaded through the same ServiceLoader the registry uses, so
                // the artifact the code source reports is the jar under test.
                assertThat(ServiceLoader.load(AssertionProvider.class, loader).stream())
                        .as("the fixture jar is discoverable at all").isNotEmpty();
                Map<String, AssertionProvider> providers = ExtensionRegistry.assertionProviders(
                        new ExtensionPolicy(allowed, false, 0, Set.of(), Set.of()));
                return new Decision(providers.containsKey("outside"),
                        refusals.toString(StandardCharsets.UTF_8));
            } finally {
                Thread.currentThread().setContextClassLoader(was);
            }
        } finally {
            System.setErr(wasErr);
        }
    }

    private record Decision(boolean loaded, String refusal) {
    }

    private static String digestOf(Path jar) throws Exception {
        return ContentDigest.sha256Uri(Files.readAllBytes(jar));
    }

    @Test
    void aJarTheOperatorPermittedByDigestLoads() throws Exception {
        Path jar = jarDeclaring("reviewed", manifestFor("0.1"));

        assertThat(decide(jar, Set.of(digestOf(jar))).loaded())
                .as("these bytes, permitted by their digest").isTrue();
    }

    @Test
    void anotherJarOfferingTheSameClassDoesNot() throws Exception {
        // The half that makes a digest worth having. Same class name, same
        // service declaration, different bytes — and an operator who permitted
        // the reviewed one has not permitted this.
        Path reviewed = jarDeclaring("reviewed", manifestFor("0.1"));
        Path substituted = jarDeclaring("substituted",
                manifestFor("0.1") + "# and something nobody reviewed\n");

        assertThat(digestOf(substituted)).isNotEqualTo(digestOf(reviewed));

        Decision decision = decide(substituted, Set.of(digestOf(reviewed)));

        assertThat(decision.loaded()).isFalse();
        assertThat(decision.refusal())
                .as("and the refusal quotes what to paste to permit it")
                .contains(digestOf(substituted));
    }

    @Test
    void aClassNameStillWorksAndTheRefusalSaysWhatItIsWorth() throws Exception {
        // Naming the class remains permitted — ExtensionPolicy has always said
        // "digests or names" — and it means "any jar offering that class",
        // which the diagnostic states rather than leaving to be discovered.
        Path jar = jarDeclaring("named", manifestFor("0.1"));

        assertThat(decide(jar, Set.of(CLASS_NAME)).loaded()).isTrue();
        assertThat(decide(jar, Set.of()).refusal())
                .contains("permit any jar offering that class");
    }

    @Test
    void aPluginBuiltForAnotherFaultoraIsRefusedWithBothVersions() throws Exception {
        // Rather than a NoSuchMethodError halfway through somebody's run.
        Path future = jarDeclaring("future", """
                id: outside-assertions
                version: 2.0.0
                requiresApi:
                  from: 99.0
                """);

        Decision decision = decide(future, Set.of(digestOf(future)));

        assertThat(decision.loaded()).isFalse();
        assertThat(decision.refusal())
                .contains("99.0", ExtensionRegistry.runningVersion());
    }

    @Test
    void aVersionRangeComparesNumbersRatherThanText() throws Exception {
        // 0.10 sorts before 0.9 as text, which would refuse every plugin built
        // for the release this one follows.
        Path jar = jarDeclaring("recent", manifestFor("0.9"));

        assertThat(decide(jar, Set.of(digestOf(jar))).loaded())
                .as("0.10 is after 0.9, whatever a string comparison thinks")
                .isTrue();
    }

    @Test
    void aPluginWithNoManifestCannotSayWhatItWasBuiltFor() throws Exception {
        Path jar = jarDeclaring("silent", null);

        Decision decision = decide(jar, Set.of(digestOf(jar)));

        assertThat(decision.loaded()).isFalse();
        assertThat(decision.refusal()).contains("META-INF/faultora-plugin.yaml");
    }

    @Test
    void aPluginThatAsksForSomethingNobodyCanHoldItToIsRefused() throws Exception {
        // Declaring a capability is not being granted one, and while the
        // plugin shares this JVM's sockets nothing can hold it to the
        // declaration. Refusing is the honest answer until it does not.
        Path reaching = jarDeclaring("reaching", """
                id: outside-assertions
                version: 1.4.0
                requiresApi:
                  from: 0.1
                capabilities:
                  networkDestinations: [api.example.com]
                """);

        Decision decision = decide(reaching, Set.of(digestOf(reaching)));

        assertThat(decision.loaded()).isFalse();
        assertThat(decision.refusal())
                .contains("api.example.com", "ADR-023");
    }

    @Test
    void aBuiltInNeedsNoneOfThis() {
        // The built-ins ship with the release and are reviewed with it, so
        // they carry no manifest and are not refused for lacking one.
        assertThat(ExtensionRegistry.assertionProviders(
                new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of())))
                .as("a run with no extensions still has its own assertions")
                .isNotEmpty();
    }
}
