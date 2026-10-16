package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.runtime.CatalogDescription;
import dev.faultora.runtime.PlanPreview;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RunPreparation;
import dev.faultora.runtime.RunInputs;
import dev.faultora.runtime.RuntimeCapabilities;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.ConnectorContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Three preparation operations; execution authority is deliberately absent. */
final class McpTools implements AutoCloseable {
    private static final ExtensionPolicy BUILT_INS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    private static final List<String> NAMES = List.of("list_capabilities", "describe_catalog", "prepare_run");
    private final McpConfiguration configuration;
    private final Map<String, SealedPreparation> preparations = new LinkedHashMap<>();
    private boolean closed;

    private record SealedPreparation(PreparedRun plan, RunPreparation.Request request,
                                     PreparedRun.Settings settings) {
        boolean expired() {
            try {
                plan.requireCurrent(request, settings);
                return false;
            } catch (PreparedRun.Refused refused) {
                return true;
            }
        }
    }

    private static final class Refused extends RuntimeException {
        private Refused(String code) {
            super(code);
        }
    }

    McpTools(McpConfiguration configuration) {
        this.configuration = configuration;
    }

    JsonNode definitions() {
        var tools = McpJson.MAPPER.createArrayNode();
        for (String name : NAMES) {
            var tool = tools.addObject().put("name", name);
            tool.put("description", switch (name) {
                case "list_capabilities" -> "Describe installed capabilities and declared catalog IDs";
                case "describe_catalog" -> "Describe one declared catalog without source content or endpoints";
                default -> "Prepare ordinary scenario YAML without executing targets or faults";
            });
            var schema = tool.putObject("inputSchema");
            schema.put("type", "object").put("additionalProperties", false);
            var properties = schema.putObject("properties");
            var required = schema.putArray("required");
            if (name.equals("describe_catalog")) {
                properties.putObject("catalogId").put("type", "string").put("maxLength", 64);
                required.add("catalogId");
            } else if (name.equals("prepare_run")) {
                properties.putObject("scenario").put("type", "string").put("maxLength", 262_144);
                var ids = properties.putObject("catalogIds");
                ids.put("type", "array").put("minItems", 1).put("maxItems", 16).put("uniqueItems", true);
                ids.putObject("items").put("type", "string").put("maxLength", 64);
                properties.putObject("seed").put("type", "integer")
                        .put("minimum", Long.MIN_VALUE).put("maximum", Long.MAX_VALUE);
                properties.putObject("inputs").put("type", "object");
                required.add("scenario").add("catalogIds");
            }
            tool.putObject("annotations").put("readOnlyHint", !name.equals("prepare_run"))
                    .put("destructiveHint", false).put("openWorldHint", false);
        }
        return McpJson.MAPPER.createObjectNode().set("tools", tools);
    }

    JsonNode call(String name, JsonNode arguments) throws IOException {
        try {
            if (closed) {
                throw new Refused("SESSION_CLOSED");
            }
            JsonNode result = switch (name) {
                case "list_capabilities" -> capabilities(arguments);
                case "describe_catalog" -> describe(arguments);
                case "prepare_run" -> prepare(arguments);
                default -> throw new Refused("UNKNOWN_TOOL");
            };
            bounded(result);
            return wrap(result, result.has("code"));
        } catch (Refused refused) {
            return wrap(McpJson.MAPPER.createObjectNode().put("code", refused.getMessage()), true);
        } catch (IllegalArgumentException invalid) {
            return wrap(McpJson.MAPPER.createObjectNode().put("code", "INVALID_ARGUMENT"), true);
        } catch (CatalogAssembly.AssemblyException invalid) {
            return wrap(McpJson.MAPPER.createObjectNode().put("code", "CATALOG_REFUSED"), true);
        } catch (PreparedRun.Refused refused) {
            return wrap(McpJson.MAPPER.createObjectNode().put("code", "PREPARATION_REFUSED"), true);
        }
    }

    private RuntimeCapabilities runtime() {
        return RuntimeCapabilities.describe(BUILT_INS, configuration.targets(),
                configuration.targets().allowedFaultTypes());
    }

    private JsonNode capabilities(JsonNode arguments) {
        McpConfiguration.fields(arguments, Set.of());
        return McpJson.MAPPER.valueToTree(Map.of("runtime", runtime(), "tools", NAMES,
                "catalogIds", configuration.catalogs().keySet().stream().sorted().toList(),
                "executionAvailable", false));
    }

    private CatalogAssembly.Document catalog(String id) {
        CatalogAssembly.Document document = configuration.catalogs().get(id);
        if (document == null) {
            throw new Refused("UNKNOWN_CATALOG");
        }
        return document;
    }

    private JsonNode describe(JsonNode arguments) {
        McpConfiguration.fields(arguments, Set.of("catalogId"));
        var catalog = CatalogAssembly.assemble(List.of(catalog(McpConfiguration.text(arguments, "catalogId"))),
                BUILT_INS);
        return McpJson.MAPPER.valueToTree(CatalogDescription.describe(catalog, configuration.targets()));
    }

    private JsonNode prepare(JsonNode arguments) throws IOException {
        McpConfiguration.fields(arguments, Set.of("scenario", "catalogIds", "seed", "inputs"));
        String scenario = McpConfiguration.text(arguments, "scenario");
        if (scenario.getBytes(StandardCharsets.UTF_8).length > 262_144) {
            throw new Refused("SCENARIO_TOO_LARGE");
        }
        JsonNode ids = arguments.path("catalogIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > 16) {
            throw new IllegalArgumentException();
        }
        var seen = new java.util.HashSet<String>();
        var documents = new ArrayList<CatalogAssembly.Document>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !seen.add(id.asText())) {
                throw new IllegalArgumentException();
            }
            documents.add(catalog(id.asText()));
        }
        JsonNode seed = arguments.path("seed");
        if (!seed.isMissingNode() && (!seed.isIntegralNumber() || !seed.canConvertToLong())) {
            throw new IllegalArgumentException();
        }
        JsonNode inputs = arguments.has("inputs") ? arguments.get("inputs") : McpJson.MAPPER.createObjectNode();
        if (!inputs.isObject() || McpJson.MAPPER.writeValueAsBytes(inputs).length > 65_536) {
            throw new IllegalArgumentException();
        }
        preparations.values().removeIf(SealedPreparation::expired);
        if (preparations.size() >= 32) {
            throw new Refused("PREPARATION_LIMIT");
        }
        var request = new RunPreparation.Request(scenario, documents, "", BUILT_INS, configuration.targets(),
                RunArtifacts.newRunId(), seed.isMissingNode() ? 0 : seed.longValue(), null);
        var validation = RunPreparation.prepare(request);
        if (!validation.isSuccess()) {
            var refused = McpJson.MAPPER.createObjectNode().put("code", "PLAN_REFUSED");
            refused.set("preview", McpJson.MAPPER.valueToTree(
                    PlanPreview.describe(validation, runtime(), configuration.targets())));
            return refused;
        }
        Map<String, Object> bindings = McpJson.MAPPER.convertValue(inputs,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        bindings = RunInputs.resolve(validation.scenario(), bindings);
        var settings = new PreparedRun.Settings(ExpressionContext.builder().inputs(bindings)
                .runMetadata(Map.of("seed", request.seed())).build(),
                new ConnectorContext(EvidencePolicy.MINIMAL, ignored -> {
                    throw new IllegalStateException("MCP preparation cannot resolve secrets");
                }, 5000, 30_000, 60_000, Map.of()), false, configuration.targets().allowedFaultTypes());
        PreparedRun prepared = PreparedRun.prepare(request, settings, Duration.ofMinutes(5));
        String id = UUID.randomUUID().toString();
        var response = McpJson.MAPPER.createObjectNode().put("preparedId", id)
                .put("preparationDigest", prepared.preparationDigest()).put("catalogDigest", prepared.catalogDigest())
                .put("runId", request.runId().value()).put("maxLifetimeMs", 300_000)
                .put("executionAuthorized", false);
        response.set("preview", McpJson.MAPPER.valueToTree(prepared.preview()));
        bounded(response);
        preparations.put(id, new SealedPreparation(prepared, request, settings));
        return response;
    }

    private static void bounded(JsonNode result) throws IOException {
        if (McpJson.MAPPER.writeValueAsBytes(result).length > McpJson.MAX_RESULT_BYTES) {
            throw new Refused("RESULT_TOO_LARGE");
        }
    }

    private static JsonNode wrap(JsonNode data, boolean error) throws IOException {
        var result = McpJson.MAPPER.createObjectNode().put("isError", error);
        result.set("structuredContent", data);
        result.putArray("content").addObject().put("type", "text")
                .put("text", McpJson.MAPPER.writeValueAsString(data));
        return result;
    }

    @Override
    public void close() {
        preparations.clear();
        closed = true;
    }
}
