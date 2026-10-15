package dev.faultora.runtime;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.DataSchema;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.identifier.SchemaId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDigestTest {
    @Test
    void theVersionedPreimageHasAnExplicitFieldOrder() {
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"),
                List.of(), List.of(), Map.of(), Map.of(), List.of());
        String expected = "faultora.catalog.v1\n"
                + "{\"authentication\":{},\"operations\":[],\"schemas\":{},"
                + "\"targets\":[],\"version\":\"synthetic\",\"workflows\":[]}";

        assertThat(RunPreparation.digest(catalog)).isEqualTo(ContentDigest.sha256Uri(expected));
    }

    @Test
    void schemaAndNestedObjectInsertionOrderDoesNotChangeTheDigest() {
        assertThat(RunPreparation.digest(catalog(false, "string", List.of("a", "b"))))
                .isEqualTo(RunPreparation.digest(catalog(true, "string", List.of("a", "b"))));
    }

    @Test
    void changingANestedValueChangesTheDigest() {
        assertThat(RunPreparation.digest(catalog(false, "string", List.of("a", "b"))))
                .isNotEqualTo(RunPreparation.digest(catalog(false, "integer", List.of("a", "b"))));
    }

    @Test
    void arrayOrderRemainsSignificant() {
        assertThat(RunPreparation.digest(catalog(false, "string", List.of("a", "b"))))
                .isNotEqualTo(RunPreparation.digest(catalog(false, "string", List.of("b", "a"))));
    }

    @Test
    void integersBeyondDoublePrecisionRemainDistinct() {
        assertThat(RunPreparation.digest(numeric(new BigInteger("9007199254740992"))))
                .isNotEqualTo(RunPreparation.digest(numeric(new BigInteger("9007199254740993"))));
    }

    @Test
    void exactDecimalValuesRemainDistinct() {
        assertThat(RunPreparation.digest(numeric(new BigDecimal("12345678901234567890.000000000000000001"))))
                .isNotEqualTo(RunPreparation.digest(numeric(new BigDecimal("12345678901234567890.000000000000000002"))));
    }

    @Test
    void decimalScaleIsPreservedByThisFormat() {
        assertThat(RunPreparation.digest(numeric(new BigDecimal("1.00"))))
                .isNotEqualTo(RunPreparation.digest(numeric(new BigDecimal("1.0"))));
    }

    @Test
    @Timeout(90)
    void publicPreparationProducesTheSameFingerprintInFreshJvms() throws Exception {
        String expected = CatalogProbe.prepareDigest();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int attempt = 0; attempt < 8; attempt++) {
            Process process = new ProcessBuilder(java, "-cp", classpath, CatalogProbe.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("fresh JVM %d completes", attempt).isTrue();
                assertThat(process.exitValue()).as("fresh JVM %d exit", attempt).isZero();
                String output = new String(process.getInputStream().readNBytes(1024), StandardCharsets.UTF_8).strip();
                assertThat(output).isEqualTo(expected);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static ApiCatalog catalog(boolean reverse, String type, List<String> ordered) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("alpha", Map.of("type", type));
        properties.put("zeta", Map.of("enum", ordered));
        Map<SchemaId, DataSchema> schemas = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(List.of("alpha", "zeta"));
        if (reverse) {
            properties = new LinkedHashMap<>(properties.reversed());
            java.util.Collections.reverse(ids);
        }
        for (String id : ids) {
            SchemaId schemaId = new SchemaId(id);
            schemas.put(schemaId, new DataSchema(schemaId, "object", "synthetic",
                    Map.of("properties", properties)));
        }
        return new ApiCatalog(new CatalogVersion("synthetic"), List.of(), List.of(), schemas, Map.of(), List.of());
    }

    private static ApiCatalog numeric(Number number) {
        SchemaId id = new SchemaId("number");
        return new ApiCatalog(new CatalogVersion("synthetic"), List.of(), List.of(),
                Map.of(id, new DataSchema(id, "number", "synthetic", Map.of("const", number))), Map.of(), List.of());
    }

    public static final class CatalogProbe {
        public static void main(String[] args) {
            System.out.println(prepareDigest());
        }

        static String prepareDigest() {
            String scenario = """
                    apiVersion: faultora.dev/v1
                    kind: Scenario
                    metadata: {name: digest}
                    execute:
                      - id: read
                        operationId: read
                    """;
            var result = RunPreparation.prepare(new RunPreparation.Request(scenario, List.of(),
                    "http://127.0.0.1:1", new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of()),
                    new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY),
                            50, 2, 10_000, 1024, Set.of(), Set.of()), new RunId("synthetic"), 42, null));
            if (!result.isSuccess()) {
                throw new IllegalStateException("Synthetic digest preparation failed");
            }
            return result.compilation().plan().catalogDigest();
        }
    }
}
