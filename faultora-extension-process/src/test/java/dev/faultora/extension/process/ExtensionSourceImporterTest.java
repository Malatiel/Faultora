package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.ExtensionCodec;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionSourceImporterTest {
    private static final String TYPE = "openapi-3.0";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration LIFETIME = Duration.ofSeconds(15);
    private static final ExtensionProcess.Provider SELECTOR = new ExtensionProcess.Provider("importer", TYPE);
    private static final ExtensionProcess.Declaration DECLARATION =
            new ExtensionProcess.Declaration("fixture.importer", "1", Set.of(SELECTOR));
    private static final String VALID = "openapi: 3.0.3\ncomponents: {schemas: {Example: {type: string}}}\n"
            + "schema: {$ref: '#/components/schemas/Example'}\n";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path directory;

    @Test
    void importerWireMatchesTheVersionOneFixtures() throws Exception {
        JsonNode input = fixture("request").path("data").path("input");
        var decoded = ImportWire.readInput(input, TYPE, JsonFrameCodec.MAX_FRAME_BYTES);
        assertThat(ImportWire.input(decoded.source(), decoded.context(), TYPE, JsonFrameCodec.MAX_FRAME_BYTES))
                .isEqualTo(input);
        for (String kind : List.of("result", "failure")) {
            var value = fixture(kind).path("data").path("value");
            assertThat(ImportWire.result(ImportWire.readResult(value))).isEqualTo(value);
        }
    }

    @Test
    void catalogErrorsWarningsAndMetadataRoundTripThroughVerifiedChild() throws Exception {
        try (var staged = stage(FixtureImporter.class, true);
             var importer = ExtensionSourceImporter.start(staged, runtimeClasspath(), DECLARATION, TIMEOUT, LIFETIME)) {
            assertThat(importer.supportedTypes()).containsExactly(TYPE);
            var context = context(65536);
            var result = importer.importSource(VALID, context);
            assertThat(result.isSuccess()).isTrue();
            var catalog = result.catalog();
            var fixture = FixtureImporter.fixtureCatalog();
            assertThat(catalog.version()).isEqualTo(fixture.version());
            assertThat(catalog.targets()).containsExactlyElementsOf(fixture.targets());
            assertThat(catalog.operations()).containsExactlyElementsOf(fixture.operations());
            var schema = catalog.schemas().get(new SchemaId("fixture.schema"));
            assertThat(schema.definition()).containsEntry("default", 1);
            assertThat(schema.definition().get("minimum")).isInstanceOfSatisfying(BigDecimal.class,
                    minimum -> assertThat(minimum).isEqualByComparingTo("0.25"));
            assertThat(result.warnings()).containsExactly("Synthetic import warning");
            assertThat(result.metadata()).isEqualTo(Map.of("synthetic", List.of(1, true)));

            var failure = importer.importSource("{reject: true}", context);
            assertThat(failure.catalog()).isNull();
            assertThat(failure.isSuccess()).isFalse();
            assertThat(failure.errors()).containsExactly(fixtureError());
            assertThat(failure.warnings()).containsExactly("Synthetic import warning");
        }
    }

    @Test
    void aReturnedSchemaCannotReintroduceExternalReferences() throws Exception {
        var result = ImportWire.result(ImportResult.success(FixtureImporter.fixtureCatalog(), List.of(), Map.of()));
        var definition = (ObjectNode) result.at("/catalog/schemas/fixture.schema/definition");
        definition.put("$ref", "external.yaml#/schema");

        assertThatThrownBy(() -> ImportWire.readResult(result))
                .isInstanceOf(IOException.class).hasMessage("MALFORMED_MESSAGE");
    }

    @Test
    void malformedOversizedAndExternalDocumentsCannotProduceACatalog() throws Exception {
        try (var staged = stage(FixtureImporter.class, true);
             var importer = ExtensionSourceImporter.start(staged, runtimeClasspath(), DECLARATION, TIMEOUT, LIFETIME)) {
            assertRefused(importer.importSource("{", context(65536)), "SOURCE_INVALID");
            assertRefused(importer.importSource("first: yes\n---\nsecond: no", context(65536)), "SOURCE_INVALID");
            assertRefused(importer.importSource("x: &external https://fixture.invalid/schema\n$ref: *external",
                    context(65536)), "SOURCE_INVALID");
            assertRefused(importer.importSource("{\"nested\":{\"$ref\":\"other.yaml#/schema\"}}",
                    context(65536)), "EXTERNAL_REFERENCE_REFUSED");
            String unicode = "{label: '日本'}";
            assertRefused(importer.importSource(unicode, context(unicode.length())), "SOURCE_TOO_LARGE");
            assertThat(importer.importSource(unicode, context(unicode.getBytes(StandardCharsets.UTF_8).length))
                    .isSuccess()).isTrue();
        }
    }

    @Test
    void aMalformedPeerCatalogClosesTheSessionWithoutReturningAPlanInput() throws Exception {
        try (var staged = stage(MalformedPeer.class, false);
             var peer = ExtensionProcess.start(staged, MalformedPeer.class.getName(),
                     runtimeClasspath(), DECLARATION, LIFETIME);
             var importer = new ExtensionSourceImporter(peer, SELECTOR, TIMEOUT)) {
            assertRefused(importer.importSource(VALID, context(65536)), "EXTENSION_IMPORT_FAILED");
            assertThat(peer.isAlive()).isFalse();
            assertRefused(importer.importSource(VALID, context(65536)), "EXTENSION_IMPORT_FAILED");
        }
    }

    private static void assertRefused(ImportResult result, String code) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.catalog()).isNull();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.metadata()).isEmpty();
        });
    }

    private ImportContext context(long maxSize) {
        return new ImportContext(TYPE, directory.resolve("parent-workspace"), Set.of("fixture.invalid"),
                8, maxSize, Map.of("fixture", true));
    }

    private static NormalizedError fixtureError() {
        return new NormalizedError(NormalizedError.ErrorCategory.VALIDATION, "SOURCE_INVALID",
                "Synthetic source error", false, Map.of("line", 2));
    }

    private static JsonNode fixture(String kind) throws IOException {
        try (var input = ExtensionSourceImporterTest.class.getResourceAsStream("/extension-wire-v1/import-" + kind + ".json")) {
            if (input == null) throw new IOException("Missing importer fixture");
            return JSON.readTree(input);
        }
    }

    private StagedArtifact stage(Class<?> entrypoint, boolean importer) throws IOException {
        Path plugin = directory.resolve("plugin.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(plugin))) {
            String entry = entrypoint.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(entry));
            try (var bytes = entrypoint.getResourceAsStream("/" + entry)) {
                bytes.transferTo(jar);
            }
            jar.closeEntry();
            if (importer) {
                jar.putNextEntry(new JarEntry("META-INF/services/" + SourceImporter.class.getName()));
                jar.write(entrypoint.getName().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        String digest = "sha256:" + HexFormat.of().formatHex(StagedArtifact.sha256().digest(Files.readAllBytes(plugin)));
        return StagedArtifact.copy(plugin, digest, directory);
    }

    private static List<Path> runtimeClasspath() throws Exception {
        Path tests = Path.of(ExtensionSourceImporterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)).map(Path::of).map(Path::toAbsolutePath).filter(path -> !path.equals(tests)).toList();
    }

    public static final class FixtureImporter implements SourceImporter {
        @Override public Set<String> supportedTypes() { return Set.of(TYPE); }

        @Override
        public ImportResult importSource(String source, ImportContext context) {
            if (!TYPE.equals(context.sourceType()) || context.maxRefDepth() != 8
                    || !Path.of(".").toAbsolutePath().normalize().equals(context.workspaceRoot())
                    || !context.allowedRefDomains().isEmpty() || !context.config().equals(Map.of("fixture", true))) {
                throw new IllegalStateException("Unexpected import context");
            }
            var warnings = List.of("Synthetic import warning");
            var metadata = Map.<String, Object>of("synthetic", List.of(1, true));
            if (source.contains("reject")) {
                var error = new NormalizedError(NormalizedError.ErrorCategory.VALIDATION, "SOURCE_INVALID",
                        "Synthetic source error", false, Map.of("line", 2));
                return new ImportResult(null, List.of(error), warnings, metadata);
            }
            return ImportResult.success(fixtureCatalog(), warnings, metadata);
        }

        static ApiCatalog fixtureCatalog() {
            var id = new SchemaId("fixture.schema");
            var target = new TargetId("fixture.target");
            var protocol = new ProtocolId("http");
            var schema = new DataSchema(id, "number", "#/components/schemas/Example",
                    Map.of("type", "number", "default", 1L, "minimum", new BigDecimal("0.2500")));
            var operation = new OperationDefinition(new OperationId("fixture.operation"), protocol, target,
                    SafetyClassification.READ_ONLY, Map.of(), null, Map.of("200", id), Map.of("path", "/fixture"));
            var definition = new TargetDefinition(target, "Fixture", "https://fixture.invalid",
                    List.of(protocol), List.of(), Map.of());
            return new ApiCatalog(new CatalogVersion("fixture-catalog"), List.of(definition),
                    List.of(operation), Map.of(id, schema), Map.of(), List.of());
        }
    }

    public static final class MalformedPeer {
        public static void main(String[] args) throws Exception {
            var codec = new ExtensionCodec(JsonFrameCodec.MAX_FRAME_BYTES);
            var hello = (ObjectNode) codec.read(System.in);
            hello.put("kind", "ready").put("version", "1.0");
            ((ObjectNode) hello.get("data")).remove("versions");
            codec.write(System.out, hello);
            System.out.flush();

            var request = (ObjectNode) codec.read(System.in);
            var data = (ObjectNode) request.get("data");
            data.remove(List.of("input", "timeoutMs"));
            var result = data.putObject("value");
            result.putObject("catalog").put("version", "synthetic-incomplete-catalog");
            result.putArray("errors");
            result.putArray("warnings");
            result.putObject("metadata");
            request.put("kind", "result");
            codec.write(System.out, request);
            System.out.flush();
            System.in.read();
        }
    }
}
