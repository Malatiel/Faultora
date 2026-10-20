package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runtime.CatalogAssembly;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolsTest {
    private JsonNode arguments(String scenario) {
        var arguments = McpJson.MAPPER.createObjectNode().put("scenario", scenario).put("seed", 42);
        arguments.putArray("catalogIds").add("demo");
        return arguments;
    }

    @Test
    void capabilitiesAndCatalogDescriptionExcludeSourceValuesAndEndpoints() throws Exception {
        try (var tools = new McpTools(McpFixtures.configuration())) {
            var result = tools.call("list_capabilities", McpJson.MAPPER.createObjectNode());
            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(result.path("structuredContent").path("catalogIds").get(0).asText()).isEqualTo("demo");
            assertThat(result.path("structuredContent").path("executionAvailable").asBoolean()).isFalse();
            var catalog = tools.call("describe_catalog", McpJson.MAPPER.createObjectNode().put("catalogId", "demo"));
            assertThat(catalog.path("isError").asBoolean()).isFalse();
            assertThat(catalog.path("structuredContent").path("operations").get(0).path("id").asText()).isEqualTo("read");
            assertThat(catalog.toString()).doesNotContain(McpFixtures.MARKER, "api.example.com", "source.yaml");
            assertThat(McpJson.MAPPER.readTree(catalog.path("content").get(0).path("text").asText()))
                    .isEqualTo(catalog.path("structuredContent"));
        }
    }

    @Test
    void catalogAndPolicyFieldsFromToolArgumentsCannotWidenAuthority() throws Exception {
        try (var tools = new McpTools(McpFixtures.configuration())) {
            for (String id : java.util.List.of("unknown", "../outside", "file:///outside", McpFixtures.MARKER)) {
                var response = tools.call("describe_catalog", McpJson.MAPPER.createObjectNode().put("catalogId", id));
                assertThat(response.path("structuredContent").path("code").asText()).isEqualTo("UNKNOWN_CATALOG");
                assertThat(response.toString()).doesNotContain(McpFixtures.MARKER);
            }
            for (String field : java.util.List.of("sourcePath", "target", "policy", "confirmed", "output")) {
                var request = (com.fasterxml.jackson.databind.node.ObjectNode) arguments(McpFixtures.SCENARIO);
                request.put(field, McpFixtures.MARKER);
                var response = tools.call("prepare_run", request);
                assertThat(response.path("structuredContent").path("code").asText()).isEqualTo("INVALID_ARGUMENT");
                assertThat(response.toString()).doesNotContain(McpFixtures.MARKER);
            }
        }
    }

    @Test
    void preparationUsesOrdinaryYamlButMakesNoTargetRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        target.start();
        try {
            String catalog = McpFixtures.CATALOG.replace("http://api.example.com/SYNTHETIC_PRIVATE_VALUE",
                    "http://127.0.0.1:" + target.getAddress().getPort());
            var configuration = new McpConfiguration(Map.of("demo", new CatalogAssembly.Document("openapi", catalog)),
                    McpFixtures.policy());
            String scenario = McpFixtures.SCENARIO + """
                    faults:
                      - id: latency
                        faultType: http-latency
                        duration: 1s
                        params: {delayMs: 1, privateValue: SYNTHETIC_PRIVATE_VALUE}
                    """;
            try (var tools = new McpTools(configuration)) {
                var first = tools.call("prepare_run", arguments(scenario)).path("structuredContent");
                var second = tools.call("prepare_run", arguments(scenario)).path("structuredContent");
                assertThat(first.path("preview").path("compiled").asBoolean()).isTrue();
                assertThat(first.path("preview").path("faults")).hasSize(1);
                assertThat(first.path("executionAuthorized").asBoolean()).isFalse();
                assertThat(first.path("preparedId").asText()).isNotBlank().isNotEqualTo(second.path("preparedId").asText());
                assertThat(first.path("runId")).isNotEqualTo(second.path("runId"));
                assertThat(first.path("preparationDigest").asText()).startsWith("sha256:");
                assertThat(first.path("preparationDigest")).isNotEqualTo(second.path("preparationDigest"));
                assertThat(first.toString()).doesNotContain(McpFixtures.MARKER, "127.0.0.1", "delayMs");
                assertThat(tools.definitions().toString()).doesNotContain("start_run", "cancel_run", "get_artifact");
            }
            assertThat(requests).hasValue(0);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void aPreparationSaysWhichCatalogAnImporterWarnedAboutWithoutRepeatingTheDocument() throws Exception {
        String hostless = """
                asyncapi: 3.0.0
                info: { title: Events, version: 1.0.0 }
                servers:
                  %s: { protocol: kafka }
                """.formatted(McpFixtures.MARKER);
        var configuration = new McpConfiguration(Map.of(
                "demo", new CatalogAssembly.Document("openapi", McpFixtures.CATALOG),
                "events", new CatalogAssembly.Document("asyncapi", hostless)), McpFixtures.policy());
        var arguments = McpJson.MAPPER.createObjectNode().put("scenario", McpFixtures.SCENARIO).put("seed", 42);
        arguments.putArray("catalogIds").add("demo").add("events");
        try (var tools = new McpTools(configuration)) {
            var result = tools.call("prepare_run", arguments);
            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(result.path("structuredContent").path("preview").path("diagnostics")).anySatisfy(diagnostic -> {
                assertThat(diagnostic.path("severity").asText()).isEqualTo("WARNING");
                assertThat(diagnostic.path("phase").asText()).isEqualTo("catalog");
                assertThat(diagnostic.path("path").asText()).isEqualTo("asyncapi");
            });
            assertThat(result.toString()).doesNotContain(McpFixtures.MARKER);
        }
    }

    @Test
    void parserCompilerAndImporterRefusalsHaveStructuredSanitizedResults() throws Exception {
        try (var tools = new McpTools(McpFixtures.configuration())) {
            for (String scenario : java.util.List.of("[invalid", McpFixtures.SCENARIO.replace("operationId: read",
                    "operationId: absent"))) {
                var result = tools.call("prepare_run", arguments(scenario));
                assertThat(result.path("isError").asBoolean()).isTrue();
                assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("PLAN_REFUSED");
                assertThat(result.path("structuredContent").has("preparedId")).isFalse();
            }
        }
        var invalid = new McpConfiguration(Map.of("demo", new CatalogAssembly.Document("openapi",
                "SYNTHETIC_PRIVATE_VALUE")), McpFixtures.policy());
        try (var tools = new McpTools(invalid)) {
            var result = tools.call("prepare_run", arguments(McpFixtures.SCENARIO));
            assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("CATALOG_REFUSED");
            assertThat(result.toString()).doesNotContain(McpFixtures.MARKER);
        }
    }

    @Test
    void seedAndInputShapesAreCheckedAndRequiredDefaultsMatchTheCli() throws Exception {
        String scenario = McpFixtures.SCENARIO + """
                inputs:
                  value: {type: string, required: true, defaultValue: SYNTHETIC_PRIVATE_VALUE}
                """;
        try (var tools = new McpTools(McpFixtures.configuration())) {
            assertThat(tools.call("prepare_run", arguments(scenario)).path("isError").asBoolean()).isFalse();
            var required = tools.call("prepare_run", arguments(scenario.replace(
                    ", defaultValue: SYNTHETIC_PRIVATE_VALUE", "")));
            assertThat(required.path("structuredContent").path("code").asText()).isEqualTo("INVALID_ARGUMENT");
            var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) arguments(scenario);
            unknown.putObject("inputs").put("unknown", McpFixtures.MARKER);
            assertThat(tools.call("prepare_run", unknown).path("isError").asBoolean()).isTrue();
            var invalidSeed = (com.fasterxml.jackson.databind.node.ObjectNode) arguments(scenario);
            invalidSeed.put("seed", "42");
            assertThat(tools.call("prepare_run", invalidSeed).path("isError").asBoolean()).isTrue();
        }
    }

    @Test
    void admissionPolicyIsPreservedInDescriptionsAndCompilation() throws Exception {
        var policy = new TargetPolicy(Set.of(), Set.of(SafetyClassification.DESTRUCTIVE), 10, 1,
                1000, 1024, Set.of(), Set.of());
        try (var tools = new McpTools(new McpConfiguration(McpFixtures.configuration().catalogs(), policy))) {
            var catalog = tools.call("describe_catalog", McpJson.MAPPER.createObjectNode().put("catalogId", "demo"));
            assertThat(catalog.path("structuredContent").path("operations").get(0).path("permitted").asBoolean()).isFalse();
            var prepared = tools.call("prepare_run", arguments(McpFixtures.SCENARIO));
            assertThat(prepared.path("structuredContent").path("code").asText()).isEqualTo("PLAN_REFUSED");
        }
    }

    @Test
    void sessionPreparationCountIsBoundedAndClosePreventsFurtherCalls() throws Exception {
        var tools = new McpTools(McpFixtures.configuration());
        try (tools) {
            for (int index = 0; index < 32; index++) {
                assertThat(tools.call("prepare_run", arguments(McpFixtures.SCENARIO)).path("isError").asBoolean())
                        .as("preparation " + index).isFalse();
            }
            assertThat(tools.call("prepare_run", arguments(McpFixtures.SCENARIO)).path("structuredContent")
                    .path("code").asText()).isEqualTo("PREPARATION_LIMIT");
        }
        assertThat(tools.call("list_capabilities", McpJson.MAPPER.createObjectNode()).path("structuredContent")
                .path("code").asText()).isEqualTo("SESSION_CLOSED");
    }

    @Test
    void oversizedCatalogDescriptionsAreRefusedWithoutExportingPartialData() throws Exception {
        StringBuilder catalog = new StringBuilder("openapi: 3.0.3\ninfo: {title: synthetic, version: '1'}\npaths:\n");
        for (int index = 0; index < 1500; index++) {
            catalog.append("  /read").append(index).append(":\n    get:\n      operationId: read")
                    .append(index).append("\n      responses: {'200': {description: synthetic}}\n");
        }
        try (var tools = new McpTools(new McpConfiguration(Map.of("demo",
                new CatalogAssembly.Document("openapi", catalog.toString())), McpFixtures.policy()))) {
            var result = tools.call("describe_catalog", McpJson.MAPPER.createObjectNode().put("catalogId", "demo"));
            assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("RESULT_TOO_LARGE");
            assertThat(result.path("structuredContent").has("operations")).isFalse();
        }
    }

    @Test
    void oversizedScenarioAndInvalidCatalogListsAreRejectedBeforeImport() throws Exception {
        try (var tools = new McpTools(McpFixtures.configuration())) {
            var oversized = tools.call("prepare_run", arguments("я".repeat(131_073)));
            assertThat(oversized.path("structuredContent").path("code").asText()).isEqualTo("SCENARIO_TOO_LARGE");
            for (JsonNode ids : java.util.List.of(McpJson.MAPPER.createArrayNode(),
                    McpJson.MAPPER.createArrayNode().add("demo").add("demo"),
                    McpJson.MAPPER.createArrayNode().addNull(), McpJson.MAPPER.createArrayNode().add(1))) {
                var request = (com.fasterxml.jackson.databind.node.ObjectNode) arguments(McpFixtures.SCENARIO);
                request.set("catalogIds", ids);
                assertThat(tools.call("prepare_run", request).path("structuredContent").path("code").asText())
                        .isEqualTo("INVALID_ARGUMENT");
            }
        }
    }
}
