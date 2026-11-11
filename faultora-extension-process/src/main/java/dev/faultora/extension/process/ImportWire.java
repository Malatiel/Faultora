package dev.faultora.extension.process;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.result.ImportResult;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.faultora.extension.process.ExtensionProcess.fields;
import static dev.faultora.extension.process.ExtensionProcess.require;
import static dev.faultora.extension.process.WireValues.encodeMap;
import static dev.faultora.extension.process.WireValues.readMap;

/** Data-only source and result payloads for the v1 importer. */
final class ImportWire {
    private static final String MALFORMED = "MALFORMED_MESSAGE";
    private static final int MAX_REF_DEPTH = 64;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> REFERENCES = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final YAMLFactory SOURCE = sourceFactory();

    record Call(String source, ImportContext context) { }

    static final class InvalidSource extends IOException {
        private InvalidSource(String code) { super(code); }
    }

    private ImportWire() { }

    static ObjectNode input(String source, ImportContext context, String selectedType, int frameLimit)
            throws IOException {
        if (context == null || !selectedType.equals(context.sourceType()) || context.maxRefDepth() < 0
                || context.maxRefDepth() > MAX_REF_DEPTH) throw new InvalidSource("SOURCE_CONTEXT_INVALID");
        int limit = context.maxDocSizeBytes() > 0
                ? (int) Math.min(frameLimit, context.maxDocSizeBytes()) : frameLimit;
        int bytes = validateSource(source, limit);
        var budget = new WireValues.InputBudget();
        budget.take(bytes);
        ObjectNode input = JSON.createObjectNode().put("sourceContent", source);
        input.putObject("context").put("sourceType", selectedType).put("maxRefDepth", context.maxRefDepth())
                .put("maxDocSizeBytes", limit).set("config", encodeMap(context.config(), budget));
        return input;
    }

    static Call readInput(JsonNode input, String selectedType, int frameLimit) throws IOException {
        fields(input, "sourceContent", "context");
        var context = input.get("context");
        fields(context, "sourceType", "maxRefDepth", "maxDocSizeBytes", "config");
        require(input.get("sourceContent").isTextual() && selectedType.equals(context.get("sourceType").textValue()),
                MALFORMED);
        String source = input.get("sourceContent").textValue();
        int depth = integer(context.get("maxRefDepth"), 0, MAX_REF_DEPTH);
        int limit = integer(context.get("maxDocSizeBytes"), 1, frameLimit);
        validateSource(source, limit);
        return new Call(source, new ImportContext(selectedType, Path.of(".").toAbsolutePath().normalize(),
                Set.of(), depth, limit, readMap(context.get("config"))));
    }

    static ObjectNode result(ImportResult result) throws IOException {
        require(result != null && result.errors() != null && result.warnings() != null && result.metadata() != null,
                MALFORMED);
        require((result.catalog() == null) != result.errors().isEmpty(), MALFORMED);
        ObjectNode value = JSON.createObjectNode();
        value.set("catalog", JSON.valueToTree(result.catalog()));
        var errors = value.putArray("errors");
        var budget = new WireValues.InputBudget();
        for (NormalizedError error : result.errors()) {
            require(error != null && error.category() != null && error.code() != null && error.message() != null,
                    MALFORMED);
            budget.take(error.code().length() + error.message().length() + 20L);
            errors.addObject().put("category", error.category().name()).put("code", error.code())
                    .put("message", error.message()).put("retryable", error.retryable())
                    .set("metadata", encodeMap(error.metadata(), budget));
        }
        var warnings = value.putArray("warnings");
        for (String warning : result.warnings()) {
            require(warning != null, MALFORMED);
            budget.take(warning.length() + 2L);
            warnings.add(warning);
        }
        value.set("metadata", encodeMap(result.metadata(), budget));
        return value;
    }

    static ImportResult readResult(JsonNode value) throws IOException {
        fields(value, "catalog", "errors", "warnings", "metadata");
        JsonNode catalog = value.get("catalog");
        JsonNode errors = value.get("errors");
        JsonNode warnings = value.get("warnings");
        require(errors.isArray() && warnings.isArray() && catalog.isNull() != errors.isEmpty(), MALFORMED);
        List<NormalizedError> decodedErrors = new ArrayList<>();
        for (JsonNode error : errors) {
            fields(error, "category", "code", "message", "retryable", "metadata");
            require(error.get("category").isTextual() && error.get("code").isTextual()
                    && error.get("message").isTextual() && error.get("retryable").isBoolean(), MALFORMED);
            NormalizedError.ErrorCategory category;
            try {
                category = NormalizedError.ErrorCategory.valueOf(error.get("category").textValue());
            } catch (IllegalArgumentException invalid) {
                throw new IOException(MALFORMED);
            }
            decodedErrors.add(new NormalizedError(category, error.get("code").textValue(),
                    error.get("message").textValue(), error.get("retryable").booleanValue(), readMap(error.get("metadata"))));
        }
        List<String> decodedWarnings = new ArrayList<>();
        for (JsonNode warning : warnings) {
            require(warning.isTextual(), MALFORMED);
            decodedWarnings.add(warning.textValue());
        }
        return new ImportResult(catalog.isNull() ? null : readCatalog(catalog), List.copyOf(decodedErrors),
                List.copyOf(decodedWarnings), readMap(value.get("metadata")));
    }

    private static ApiCatalog readCatalog(JsonNode catalog) throws IOException {
        fields(catalog, "version", "targets", "operations", "schemas", "authentication", "workflows");
        require(catalog.get("version").isTextual() && catalog.get("targets").isArray()
                && catalog.get("operations").isArray() && catalog.get("schemas").isObject()
                && catalog.get("authentication").isObject() && catalog.get("workflows").isArray(), MALFORMED);
        for (String collection : List.of("targets", "operations", "schemas", "authentication", "workflows")) {
            for (JsonNode entry : catalog.get(collection)) require(entry.isObject(), MALFORMED);
        }
        for (JsonNode schema : catalog.get("schemas")) {
            require(schema.path("definition").isObject(), MALFORMED);
            requireLocalReferences(schema.get("definition"));
        }
        ApiCatalog result;
        try {
            result = JSON.treeToValue(catalog, ApiCatalog.class);
            require(catalog.equals(ImportWire::compareJsonValues, JSON.valueToTree(result)), MALFORMED);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IOException(MALFORMED);
        }
        return result;
    }

    private static int compareJsonValues(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber() && left.isIntegralNumber() == right.isIntegralNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.equals(right) ? 0 : 1;
    }

    private static void requireLocalReferences(JsonNode value) throws IOException {
        for (var field : value.properties()) {
            if (REFERENCES.contains(field.getKey())) {
                require(field.getValue().isTextual() && field.getValue().textValue().startsWith("#"), MALFORMED);
            }
        }
        for (JsonNode child : value) requireLocalReferences(child);
    }

    private static int integer(JsonNode value, int minimum, int maximum) throws IOException {
        require(value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum, MALFORMED);
        return value.intValue();
    }

    private static int validateSource(String source, int limit) throws InvalidSource {
        if (source == null) throw new InvalidSource("SOURCE_INVALID");
        if (source.length() > limit) throw new InvalidSource("SOURCE_TOO_LARGE");
        if (source.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(source)) {
            throw new InvalidSource("SOURCE_INVALID");
        }
        int bytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limit) throw new InvalidSource("SOURCE_TOO_LARGE");
        try (var tokens = SOURCE.createParser(source)) {
            var token = tokens.nextToken();
            if (token != JsonToken.START_OBJECT && token != JsonToken.START_ARRAY) {
                throw new InvalidSource("SOURCE_INVALID");
            }
            int depth = 0;
            boolean reference = false;
            do {
                if (tokens.isCurrentAlias() || tokens.getTypeId() != null) throw new InvalidSource("SOURCE_INVALID");
                if (reference && (token != JsonToken.VALUE_STRING || !tokens.getText().startsWith("#"))) {
                    throw new InvalidSource("EXTERNAL_REFERENCE_REFUSED");
                }
                reference = token == JsonToken.FIELD_NAME && REFERENCES.contains(tokens.currentName());
                if (token == JsonToken.FIELD_NAME && "<<".equals(tokens.currentName())) {
                    throw new InvalidSource("SOURCE_INVALID");
                }
                if (token.isStructStart()) depth++;
                if (token.isStructEnd()) depth--;
                token = tokens.nextToken();
                if (depth == 0 && token != null) throw new InvalidSource("SOURCE_INVALID");
            } while (token != null);
            if (depth != 0) throw new InvalidSource("SOURCE_INVALID");
        } catch (InvalidSource refused) {
            throw refused;
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidSource("SOURCE_INVALID");
        }
        return bytes;
    }

    private static YAMLFactory sourceFactory() {
        var options = new LoaderOptions();
        options.setCodePointLimit(JsonFrameCodec.MAX_FRAME_BYTES);
        options.setNestingDepthLimit(MAX_REF_DEPTH);
        options.setMaxAliasesForCollections(0);
        return YAMLFactory.builder().loaderOptions(options).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_REF_DEPTH)
                        .maxStringLength(JsonFrameCodec.MAX_FRAME_BYTES).maxNumberLength(64).build())
                .build();
    }
}
