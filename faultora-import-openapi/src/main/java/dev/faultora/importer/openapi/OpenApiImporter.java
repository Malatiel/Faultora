package dev.faultora.importer.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;

import java.util.*;

/**
 * Imports OpenAPI 3.0 and 3.1 documents into the canonical ApiCatalog.
 * Handles operations, parameters, request bodies, responses, security schemes,
 * and server definitions. Proposes safety classifications without silently
 * authorizing destructive operations.
 */
public class OpenApiImporter implements SourceImporter {

    private static final String SOURCE_TYPE_3_0 = "openapi-3.0";
    private static final String SOURCE_TYPE_3_1 = "openapi-3.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> supportedTypes() {
        return Set.of(SOURCE_TYPE_3_0, SOURCE_TYPE_3_1);
    }

    @Override
    public ImportResult importSource(String sourceContent, ImportContext context) {
        List<dev.faultora.model.catalog.NormalizedError> errors = new ArrayList<>();

        try {
            // Parse the document
            JsonNode root = OpenApiUtils.parseDocument(sourceContent);

            // Validate it's OpenAPI 3.x
            String version = OpenApiUtils.extractVersion(root);
            if (version == null) {
                return ImportResult.failure(List.of(
                        normalizedError("VALIDATION", "NOT_OPENAPI",
                                "Document does not contain an 'openapi' field")));
            }
            if (!version.startsWith("3.")) {
                return ImportResult.failure(List.of(
                        normalizedError("VALIDATION", "UNSUPPORTED_VERSION",
                                "Unsupported OpenAPI version: " + version + ". Only 3.x is supported.")));
            }

            // Check document size
            if (context.maxDocSizeBytes() > 0 && sourceContent.length() > context.maxDocSizeBytes()) {
                return ImportResult.failure(List.of(
                        normalizedError("POLICY_VIOLATION", "DOCUMENT_TOO_LARGE",
                                "Document exceeds maximum size of " + context.maxDocSizeBytes() + " bytes")));
            }

            // Extract info
            String title = extractTitle(root);
            String contentDigest = OpenApiUtils.computeDigest(sourceContent);

            // Build the catalog
            List<TargetDefinition> targets = extractTargets(root);
            List<OperationDefinition> operations = extractOperations(root, targets);
            Map<SchemaId, DataSchema> schemas = extractSchemas(root);
            Map<AuthSchemeId, AuthSchemeDefinition> authSchemes = extractSecuritySchemes(root);

            ApiCatalog catalog = new ApiCatalog(
                    new CatalogVersion(contentDigest),
                    targets,
                    operations,
                    schemas,
                    authSchemes,
                    List.of() // workflows not extracted from OpenAPI directly
            );

            return ImportResult.success(catalog, List.of(), Map.of());

        } catch (OpenApiParseException e) {
            return ImportResult.failure(List.of(
                    normalizedError("VALIDATION", "PARSE_ERROR", e.getMessage())));
        } catch (Exception e) {
            return ImportResult.failure(List.of(
                    normalizedError("INTERNAL", "IMPORT_ERROR",
                            "Unexpected error during import: " + e.getMessage())));
        }
    }

    /**
     * Extract target definitions from servers.
     */
    private List<TargetDefinition> extractTargets(JsonNode root) {
        List<TargetDefinition> targets = new ArrayList<>();
        JsonNode servers = root.get("servers");

        if (servers != null && servers.isArray()) {
            int index = 0;
            for (JsonNode server : servers) {
                String url = OpenApiUtils.getText(server, "url");
                if (url == null || url.isBlank()) continue;

                String description = OpenApiUtils.getText(server, "description");
                String targetId = "server-" + index;

                // Extract server variables for metadata
                Map<String, Object> metadata = new LinkedHashMap<>();
                JsonNode variables = server.get("variables");
                if (variables != null) {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    variables.properties().forEach(entry -> {
                        JsonNode defaultVal = entry.getValue().get("default");
                        if (defaultVal != null) {
                            vars.put(entry.getKey(), defaultVal.asText());
                        }
                    });
                    metadata.put("variables", vars);
                }
                if (description != null) {
                    metadata.put("description", description);
                }

                targets.add(new TargetDefinition(
                        new TargetId(targetId),
                        description != null ? description : targetId,
                        url,
                        List.of(new ProtocolId("http")),
                        List.of(), // auth scheme IDs resolved later
                        metadata
                ));
                index++;
            }
        }

        // If no servers defined, add a default localhost target
        if (targets.isEmpty()) {
            targets.add(new TargetDefinition(
                    new TargetId("default"),
                    "Default target",
                    "http://localhost:8080",
                    List.of(new ProtocolId("http")),
                    List.of(),
                    Map.of()
            ));
        }

        return targets;
    }

    /**
     * Extract operation definitions from paths.
     */
    private List<OperationDefinition> extractOperations(JsonNode root, List<TargetDefinition> targets) {
        List<OperationDefinition> operations = new ArrayList<>();
        JsonNode paths = root.get("paths");

        if (paths == null || !paths.isObject()) return operations;

        String defaultTargetId = targets.isEmpty() ? "default" : targets.get(0).id().value();

        paths.properties().forEach(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            if (pathItem == null || !pathItem.isObject()) return;

            // Extract path-level parameters
            JsonNode pathParams = pathItem.get("parameters");

            // Process each HTTP method
            for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                JsonNode operation = pathItem.get(method);
                if (operation == null || !operation.isObject()) continue;

                String operationId = OpenApiUtils.getText(operation, "operationId");
                if (operationId == null || operationId.isBlank()) {
                    // Generate operation ID from method and path
                    operationId = method + path.replaceAll("[^a-zA-Z0-9]", "_");
                }

                // Determine safety classification
                SafetyClassification safety = classifyOperation(method, operation);

                // Extract inputs (parameters + request body)
                Map<String, InputDefinition> inputs = new LinkedHashMap<>();

                // Path and query parameters
                JsonNode opParams = operation.get("parameters");
                List<JsonNode> allParams = new ArrayList<>();
                if (pathParams != null && pathParams.isArray()) {
                    pathParams.forEach(allParams::add);
                }
                if (opParams != null && opParams.isArray()) {
                    opParams.forEach(allParams::add);
                }

                for (JsonNode param : allParams) {
                    String paramName = OpenApiUtils.getText(param, "name");
                    String in = OpenApiUtils.getText(param, "in");
                    if (paramName == null || in == null) continue;

                    InputDefinition.InputLocation location = switch (in) {
                        case "path" -> InputDefinition.InputLocation.PATH;
                        case "query" -> InputDefinition.InputLocation.QUERY;
                        case "header" -> InputDefinition.InputLocation.HEADER;
                        case "cookie" -> InputDefinition.InputLocation.COOKIE;
                        default -> null;
                    };
                    if (location == null) continue;

                    boolean required = param.has("required") && param.get("required").asBoolean(false);
                    String description = OpenApiUtils.getText(param, "description");
                    SchemaId schemaId = extractSchemaRef(param.get("schema"));

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (description != null) metadata.put("description", description);

                    inputs.put(paramName, new InputDefinition(
                            paramName, location, required, schemaId, null, metadata
                    ));
                }

                // Request body
                JsonNode requestBody = operation.get("requestBody");
                if (requestBody != null && requestBody.isObject()) {
                    SchemaId bodySchema = extractRequestBodySchema(requestBody);
                    boolean required = requestBody.has("required") && requestBody.get("required").asBoolean(false);
                    Map<String, Object> bodyMeta = new LinkedHashMap<>();
                    String bodyDesc = OpenApiUtils.getText(requestBody, "description");
                    if (bodyDesc != null) bodyMeta.put("description", bodyDesc);

                    inputs.put("body", new InputDefinition(
                            "body", InputDefinition.InputLocation.BODY, required,
                            bodySchema, null, bodyMeta
                    ));
                }

                // Extract response schemas (outcomes)
                Map<String, SchemaId> outcomes = new LinkedHashMap<>();
                JsonNode responses = operation.get("responses");
                if (responses != null && responses.isObject()) {
                    responses.properties().forEach(respEntry -> {
                        String statusCode = respEntry.getKey();
                        JsonNode response = respEntry.getValue();
                        if (response != null && response.isObject()) {
                            JsonNode content = response.get("content");
                            if (content != null && content.isObject()) {
                                // Try application/json first
                                JsonNode jsonContent = content.get("application/json");
                                if (jsonContent != null) {
                                    SchemaId respSchema = extractSchemaRef(jsonContent.get("schema"));
                                    if (respSchema != null) {
                                        outcomes.put(statusCode, respSchema);
                                    }
                                }
                            }
                        }
                    });
                }

                // Protocol metadata for the HTTP connector
                Map<String, Object> protocolMetadata = new LinkedHashMap<>();
                protocolMetadata.put("method", method.toUpperCase());
                protocolMetadata.put("path", path);
                String summary = OpenApiUtils.getText(operation, "summary");
                if (summary != null) protocolMetadata.put("summary", summary);

                // Collect security requirements
                JsonNode security = operation.get("security");
                if (security != null && security.isArray()) {
                    List<String> securityNames = new ArrayList<>();
                    for (JsonNode sec : security) {
                        if (sec.isObject()) {
                            sec.fieldNames().forEachRemaining(securityNames::add);
                        }
                    }
                    if (!securityNames.isEmpty()) {
                        protocolMetadata.put("security", securityNames);
                    }
                }

                SchemaId requestSchemaId = inputs.containsKey("body") ?
                        inputs.get("body").schemaId() : null;

                operations.add(new OperationDefinition(
                        new OperationId(operationId),
                        new ProtocolId("http"),
                        new TargetId(defaultTargetId),
                        safety,
                        inputs,
                        requestSchemaId,
                        outcomes,
                        protocolMetadata
                ));
            }
        });

        return operations;
    }

    /**
     * Extract schema definitions from components/schemas.
     */
    private Map<SchemaId, DataSchema> extractSchemas(JsonNode root) {
        Map<SchemaId, DataSchema> schemas = new LinkedHashMap<>();
        JsonNode components = root.get("components");
        if (components == null) return schemas;

        JsonNode schemaDefs = components.get("schemas");
        if (schemaDefs == null || !schemaDefs.isObject()) return schemas;

        schemaDefs.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode schema = entry.getValue();

            SchemaId id = new SchemaId(name);
            String schemaType = OpenApiUtils.getText(schema, "type");
            if (schemaType == null) {
                // Infer type from structure
                if (schema.has("properties")) schemaType = "object";
                else if (schema.has("items")) schemaType = "array";
                else if (schema.has("oneOf") || schema.has("anyOf") || schema.has("allOf")) {
                    schemaType = "composite";
                } else schemaType = "unknown";
            }

            // Convert schema to a Map for storage
            @SuppressWarnings("unchecked")
            Map<String, Object> definition = MAPPER.convertValue(schema, Map.class);

            schemas.put(id, new DataSchema(
                    id, schemaType, "#/components/schemas/" + name, definition
            ));
        });

        return schemas;
    }

    /**
     * Extract security scheme definitions.
     */
    private Map<AuthSchemeId, AuthSchemeDefinition> extractSecuritySchemes(JsonNode root) {
        Map<AuthSchemeId, AuthSchemeDefinition> schemes = new LinkedHashMap<>();
        JsonNode components = root.get("components");
        if (components == null) return schemes;

        JsonNode secSchemes = components.get("securitySchemes");
        if (secSchemes == null || !secSchemes.isObject()) return schemes;

        secSchemes.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode scheme = entry.getValue();

            String type = OpenApiUtils.getText(scheme, "type");
            String schemeName = OpenApiUtils.getText(scheme, "name");
            String in = OpenApiUtils.getText(scheme, "in");
            String schemeValue = OpenApiUtils.getText(scheme, "scheme");

            Map<String, Object> metadata = new LinkedHashMap<>();
            String description = OpenApiUtils.getText(scheme, "description");
            if (description != null) metadata.put("description", description);
            if (schemeValue != null) metadata.put("scheme", schemeValue);

            // Determine location
            String location = in;
            if ("apiKey".equals(type) && "header".equals(in)) {
                location = "header";
            } else if ("apiKey".equals(type) && "query".equals(in)) {
                location = "query";
            } else if ("http".equals(type)) {
                location = "header";
            }

            schemes.put(new AuthSchemeId(name), new AuthSchemeDefinition(
                    new AuthSchemeId(name),
                    type != null ? type : "unknown",
                    schemeName != null ? schemeName : name,
                    location != null ? location : "header",
                    metadata
            ));
        });

        return schemes;
    }

    /**
     * Classify an operation's safety based on HTTP method and annotations.
     */
    private SafetyClassification classifyOperation(String method, JsonNode operation) {
        // Check for explicit x-safety annotation
        JsonNode xSafety = operation.get("x-safety");
        if (xSafety != null && xSafety.isTextual()) {
            try {
                return SafetyClassification.valueOf(xSafety.asText().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Fall through to heuristic
            }
        }

        // Heuristic classification based on HTTP method
        return switch (method.toLowerCase()) {
            case "get", "head", "options" -> SafetyClassification.READ_ONLY;
            case "post" -> SafetyClassification.MUTATING;
            case "put", "patch" -> SafetyClassification.MUTATING;
            case "delete" -> SafetyClassification.DESTRUCTIVE;
            default -> SafetyClassification.UNKNOWN;
        };
    }

    /**
     * Extract a schema reference ID from a schema node.
     */
    private SchemaId extractSchemaRef(JsonNode schema) {
        if (schema == null || !schema.isObject()) return null;

        // $ref: "#/components/schemas/Foo"
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            String refStr = ref.asText();
            String name = refStr.substring(refStr.lastIndexOf('/') + 1);
            return new SchemaId(name);
        }

        return null;
    }

    /**
     * Extract request body schema reference.
     */
    private SchemaId extractRequestBodySchema(JsonNode requestBody) {
        JsonNode content = requestBody.get("content");
        if (content == null) return null;

        // Try application/json
        JsonNode json = content.get("application/json");
        if (json != null) {
            return extractSchemaRef(json.get("schema"));
        }

        // Try first available content type
        Iterator<JsonNode> it = content.elements();
        if (it.hasNext()) {
            return extractSchemaRef(it.next().get("schema"));
        }

        return null;
    }

    private String extractTitle(JsonNode root) {
        JsonNode info = root.get("info");
        if (info != null) {
            return OpenApiUtils.getText(info, "title");
        }
        return null;
    }

    private NormalizedError normalizedError(String category, String code, String message) {
        return new NormalizedError(
                NormalizedError.ErrorCategory.valueOf(category),
                code, message, false, Map.of()
        );
    }
}
