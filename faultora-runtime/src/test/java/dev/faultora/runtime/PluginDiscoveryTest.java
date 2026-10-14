package dev.faultora.runtime;

import dev.faultora.assertions.core.StatusAssertionProvider;
import dev.faultora.importer.asyncapi.AsyncApiImporter;
import dev.faultora.importer.observations.ObservationImporter;
import dev.faultora.importer.openapi.OpenApiImporter;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.contract.ReportRenderer;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.extension.PluginManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real service jars with observable initialization and constructor effects. */
class PluginDiscoveryTest {

    private static final String COMPATIBLE_MANIFEST = """
            id: synthetic-plugin
            version: 1.0
            requiresApi:
              from: 0.1
            """;

    @TempDir
    Path directory;

    private enum Kind {
        ASSERTION(AssertionProvider.class, """
                public String type() { return "outside"; }
                public dev.faultora.spi.result.AssertionResult evaluate(String type,
                        java.util.Map<String, Object> params,
                        dev.faultora.spi.context.EvidenceView evidence,
                        dev.faultora.spi.context.AssertionContext context) {
                    return dev.faultora.spi.result.AssertionResult.pass("synthetic");
                }
                """),
        RENDERER(ReportRenderer.class, """
                public String format() { return "outside"; }
                public void render(java.util.List<dev.faultora.model.events.RunEvent> events,
                        java.io.Writer output) throws java.io.IOException {
                    output.write("synthetic report");
                }
                """),
        IMPORTER(SourceImporter.class, """
                public java.util.Set<String> supportedTypes() {
                    return java.util.Set.of("outside");
                }
                public dev.faultora.spi.result.ImportResult importSource(String source,
                        dev.faultora.spi.context.ImportContext context) {
                    return dev.faultora.spi.result.ImportResult.failure(java.util.List.of());
                }
                """);

        final Class<?> service;
        final String methods;

        Kind(Class<?> service, String methods) {
            this.service = service;
            this.methods = methods;
        }

        boolean discovered(ExtensionPolicy policy) {
            return switch (this) {
                case ASSERTION -> ExtensionRegistry.assertionProviders(policy).containsKey("outside");
                case RENDERER -> ExtensionRegistry.renderers(policy).containsKey("outside");
                case IMPORTER -> ExtensionRegistry.importerFor("outside", policy) != null;
            };
        }
    }

    private enum Refusal { UNLISTED, NO_MANIFEST, INCOMPATIBLE, CAPABILITIES }

    static Stream<Arguments> refusedPlugins() {
        return Arrays.stream(Kind.values()).flatMap(kind ->
                Arrays.stream(Refusal.values()).map(reason -> Arguments.of(kind, reason)));
    }

    @ParameterizedTest
    @MethodSource("refusedPlugins")
    void refusalHappensBeforeInitializationOrConstruction(Kind kind, Refusal reason)
            throws Exception {
        String manifest = switch (reason) {
            case UNLISTED -> COMPATIBLE_MANIFEST;
            case NO_MANIFEST -> null;
            case INCOMPATIBLE -> COMPATIBLE_MANIFEST.replace("from: 0.1", "from: 99.0");
            case CAPABILITIES -> COMPATIBLE_MANIFEST
                    + "capabilities:\n  networkDestinations: [api.example.com]\n";
        };
        Fixture fixture = fixture(kind, "somebody.elses.OutsideProvider", manifest);
        try {
            assertThat(discover(fixture, reason == Refusal.UNLISTED ? Set.of() : fixture.allowed(), false))
                    .isFalse();
            fixture.assertNotExecuted();
        } finally {
            fixture.clearMarkers();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFaultoraPackageDoesNotMakeAnExternalJarBuiltIn(Kind kind) throws Exception {
        Fixture fixture = fixture(kind, "dev.faultora.untrusted.OutsideProvider", null);
        try {
            assertThat(discover(fixture, Set.of(), false)).isFalse();
            fixture.assertNotExecuted();
        } finally {
            fixture.clearMarkers();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anExplicitlyPermittedCompatiblePluginStillRunsInThatPackage(Kind kind) throws Exception {
        Fixture fixture = fixture(kind, "dev.faultora.untrusted.OutsideProvider", COMPATIBLE_MANIFEST);
        try {
            assertThat(discover(fixture, fixture.allowed(), false)).isTrue();
            assertThat(System.getProperty(fixture.marker() + ".initialized")).isEqualTo("true");
            assertThat(System.getProperty(fixture.marker() + ".constructed")).isEqualTo("true");
        } finally {
            fixture.clearMarkers();
        }
    }

    @Test
    void evenTheNameOfAnActualBuiltInCannotSubstituteItsClassIdentity() throws Exception {
        Fixture fixture = fixture(Kind.ASSERTION, StatusAssertionProvider.class.getName(), null);
        try {
            assertThat(discover(fixture, Set.of(), true)).isFalse();
            fixture.assertNotExecuted();
        } finally {
            fixture.clearMarkers();
        }
    }

    @Test
    void allShippedServicesWorkWithoutAnExtensionGrant() {
        ExtensionPolicy policy = new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
        assertThat(ExtensionRegistry.assertionProviders(policy)).containsOnlyKeys(
                "status", "header", "jsonpath", "duration", "schema", "event-count",
                "event-unique", "event-correlation", "event-sequence", "row-count",
                "row-balance", "row-unique", "row-value");
        assertThat(ExtensionRegistry.renderers(policy)).containsOnlyKeys("console", "json", "junit", "html");
        assertThat(ExtensionRegistry.importerFor("openapi", policy)).isExactlyInstanceOf(OpenApiImporter.class);
        assertThat(ExtensionRegistry.importerFor("asyncapi", policy)).isExactlyInstanceOf(AsyncApiImporter.class);
        assertThat(ExtensionRegistry.importerFor("observations", policy)).isExactlyInstanceOf(ObservationImporter.class);
    }

    private Fixture fixture(Kind kind, String className, String manifest) throws Exception {
        String marker = "faultora.test.plugin." + UUID.randomUUID();
        Path classes = Files.createDirectories(directory.resolve(marker));
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        Path source = classes.resolve(simpleName + ".java");
        Files.writeString(source, """
                package %s;
                public class %s implements %s {
                    static { System.setProperty("%s.initialized", "true"); }
                    public %s() { System.setProperty("%s.constructed", "true"); }
                    %s
                }
                """.formatted(className.substring(0, className.lastIndexOf('.')),
                simpleName, kind.service.getName(), marker, simpleName, marker, kind.methods));
        ByteArrayOutputStream complaints = new ByteArrayOutputStream();
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the fixture needs a JDK compiler").isNotNull();
        int result = compiler.run(null, null, complaints, "-cp", compilerClasspath(),
                "-d", classes.toString(), source.toString());
        assertThat(result).as(() -> "fixture compilation: " + complaints).isZero();

        Path jar = directory.resolve(marker + ".jar");
        String classEntry = className.replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, classEntry, Files.readAllBytes(classes.resolve(classEntry)));
            write(output, "META-INF/services/" + kind.service.getName(),
                    className.getBytes(StandardCharsets.UTF_8));
            if (manifest != null) {
                write(output, PluginManifest.LOCATION, manifest.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new Fixture(kind, className, jar, marker);
    }

    private static String compilerClasspath() {
        return Stream.of(AssertionProvider.class, RunEvent.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (java.net.URISyntaxException invalidLocation) {
                        throw new IllegalStateException(invalidLocation);
                    }
                }).distinct().collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static boolean discover(Fixture fixture, Set<String> allowed, boolean shadowBuiltIn)
            throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{fixture.jar().toUri().toURL()}, PluginDiscoveryTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!shadowBuiltIn || !name.equals(fixture.className())) {
                    return super.loadClass(name, resolve);
                }
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }) {
            if (shadowBuiltIn) {
                assertThat(loader.loadClass(fixture.className())).isNotSameAs(StatusAssertionProvider.class);
            }
            Thread.currentThread().setContextClassLoader(loader);
            return fixture.kind().discovered(new ExtensionPolicy(allowed, false, 0, Set.of(), Set.of()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private record Fixture(Kind kind, String className, Path jar, String marker) {
        Set<String> allowed() throws Exception {
            return Set.of(ContentDigest.sha256Uri(Files.readAllBytes(jar)));
        }

        void assertNotExecuted() {
            assertThat(System.getProperty(marker + ".initialized")).as("static initializer").isNull();
            assertThat(System.getProperty(marker + ".constructed")).as("constructor").isNull();
        }

        void clearMarkers() {
            System.clearProperty(marker + ".initialized");
            System.clearProperty(marker + ".constructed");
        }
    }
}
