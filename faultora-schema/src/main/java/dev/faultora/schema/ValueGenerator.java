package dev.faultora.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates request values from catalog schemas.
 * <p>
 * Two properties matter more than coverage of the JSON Schema specification:
 * <ul>
 *   <li><b>Reproducibility.</b> The same seed and schema always produce the
 *       same value. Randomness comes from one seeded generator consumed in a
 *       fixed traversal order, never from a clock or a hash of object
 *       identity.</li>
 *   <li><b>Honesty about limits.</b> A construct this generator cannot satisfy
 *       — a regular expression, an impossible numeric range — raises
 *       {@link SchemaException} naming the field, so the scenario fails before
 *       sending a request the contract already rejects. It never emits a value
 *       it knows to be invalid under the valid strategy.</li>
 * </ul>
 */
public final class ValueGenerator {

    /** Deepest nesting generated before a schema is considered unbounded. */
    public static final int MAX_DEPTH = 12;

    /** Characters used for generated strings: readable and URL-safe. */
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Extra characters generated beyond a string's minimum length. */
    private static final int STRING_PADDING = 6;

    /** Fixed instant generated date-time values are offset from. */
    private static final Instant EPOCH_BASE = Instant.parse("2020-01-01T00:00:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaCatalog catalog;
    private final SchemaValidator validator;

    public ValueGenerator(SchemaCatalog catalog) {
        this.catalog = catalog;
        this.validator = new SchemaValidator(catalog);
    }

    /**
     * Generate one value.
     *
     * @param schema the schema to satisfy
     * @param seed   seed making the result reproducible
     * @param spec   strategy and example handling
     * @return the generated value, and the constraint broken when the strategy
     *         is {@link GenerationStrategy#INVALID}
     * @throws SchemaException when the schema cannot be honoured
     */
    public GenerationResult generate(JsonNode schema, long seed, GenerationSpec spec) {
        Random random = new Random(seed);
        GenerationSpec effective = spec == null ? GenerationSpec.DEFAULT : spec;
        GenerationStrategy strategy = effective.strategy();

        // An invalid payload is a valid one with exactly one constraint broken:
        // anything else risks several violations at once, and then a rejecting
        // target proves nothing about the constraint under test.
        GenerationStrategy shape =
                strategy == GenerationStrategy.INVALID ? GenerationStrategy.VALID : strategy;
        JsonNode value = generateNode(
                schema, new GenerationSpec(shape, effective.preferExamples()), random, "$", 0);

        if (strategy != GenerationStrategy.INVALID) {
            return GenerationResult.valid(value);
        }
        String violation = ConstraintBreaker.breakOne(
                value, catalog.dereference(schema, "$"), catalog, random);
        return new GenerationResult(value, violation);
    }

    private JsonNode generateNode(
            JsonNode rawSchema, GenerationSpec spec, Random random, String path, int depth) {
        JsonNode schema = catalog.dereference(rawSchema, path);
        if (schema == null || !schema.isObject()) {
            throw new SchemaException(path, "no schema to generate from");
        }
        if (depth > MAX_DEPTH) {
            throw new SchemaException(path,
                    "schema nests deeper than " + MAX_DEPTH + " levels; supply this value "
                            + "explicitly instead of generating it");
        }

        JsonNode constant = schema.get("const");
        if (constant != null) {
            return constant.deepCopy();
        }
        if (spec.preferExamples() && schema.hasNonNull("example")) {
            JsonNode example = schema.get("example");
            // An authored example is preferred, but not trusted: stale
            // examples are common, and sending one that its own schema
            // rejects would blame the target for a defect in the document.
            if (validator.isValid(example, schema)) {
                return example.deepCopy();
            }
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !enumValues.isEmpty()) {
            int index = spec.strategy() == GenerationStrategy.BOUNDARY
                    ? 0 : random.nextInt(enumValues.size());
            return enumValues.get(index).deepCopy();
        }

        JsonNode composed = composedSchema(schema, path);
        if (composed != null) {
            return generateNode(composed, spec, random, path, depth + 1);
        }
        JsonNode branches = firstArray(schema, "oneOf", "anyOf");
        if (branches != null) {
            int index = spec.strategy() == GenerationStrategy.BOUNDARY
                    ? 0 : random.nextInt(branches.size());
            return generateNode(branches.get(index), spec, random, path, depth + 1);
        }

        return switch (typeOf(schema, path)) {
            case "object" -> generateObject(schema, spec, random, path, depth);
            case "array" -> generateArray(schema, spec, random, path, depth);
            case "string" -> MAPPER.getNodeFactory().textNode(
                    generateString(schema, spec, random, path));
            case "integer" -> MAPPER.getNodeFactory().numberNode(
                    generateInteger(schema, spec, random, path));
            case "number" -> MAPPER.getNodeFactory().numberNode(
                    generateNumber(schema, spec, random, path));
            case "boolean" -> MAPPER.getNodeFactory().booleanNode(
                    spec.strategy() == GenerationStrategy.BOUNDARY || random.nextBoolean());
            case "null" -> MAPPER.getNodeFactory().nullNode();
            default -> throw new SchemaException(path,
                    "unsupported schema type: " + typeOf(schema, path));
        };
    }

    private ObjectNode generateObject(
            JsonNode schema, GenerationSpec spec, Random random, String path, int depth) {
        ObjectNode object = MAPPER.createObjectNode();
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return object;
        }
        List<String> required = requiredProperties(schema);

        properties.properties().forEach(property -> {
            String name = property.getKey();
            // The boundary strategy sends the smallest accepted payload, so it
            // carries required properties only.
            boolean include = spec.strategy() != GenerationStrategy.BOUNDARY
                    || required.contains(name);
            if (!include) {
                return;
            }
            String childPath = path + "." + name;
            try {
                object.set(name, generateNode(
                        property.getValue(), spec, random, childPath, depth + 1));
            } catch (SchemaException unsupported) {
                // An optional property that cannot be generated is left out;
                // a required one has no such escape.
                if (required.contains(name)) {
                    throw unsupported;
                }
            }
        });
        return object;
    }

    private ArrayNode generateArray(
            JsonNode schema, GenerationSpec spec, Random random, String path, int depth) {
        JsonNode items = schema.get("items");
        if (items == null) {
            throw new SchemaException(path, "array schema declares no items");
        }
        int minItems = schema.path("minItems").asInt(1);
        int maxItems = schema.path("maxItems").asInt(Math.max(minItems, 1));
        int size = spec.strategy() == GenerationStrategy.BOUNDARY
                ? minItems : Math.max(minItems, Math.min(maxItems, minItems + 1));

        ArrayNode array = MAPPER.createArrayNode();
        for (int index = 0; index < size; index++) {
            array.add(generateNode(items, spec, random, path + "[" + index + "]", depth + 1));
        }
        return array;
    }

    private String generateString(
            JsonNode schema, GenerationSpec spec, Random random, String path) {
        if (schema.hasNonNull("pattern")) {
            throw new SchemaException(path,
                    "values constrained by a regular expression cannot be generated; "
                            + "supply this value explicitly with inputs");
        }
        String format = schema.path("format").asText(null);
        if (format != null) {
            String formatted = generateFormatted(format, random);
            if (formatted != null) {
                return formatted;
            }
        }
        int minLength = schema.path("minLength").asInt(1);
        int maxLength = schema.path("maxLength").asInt(Integer.MAX_VALUE);
        if (maxLength < minLength) {
            throw new SchemaException(path,
                    "maxLength " + maxLength + " is below minLength " + minLength);
        }
        int length = spec.strategy() == GenerationStrategy.BOUNDARY
                ? Math.max(minLength, 1)
                : Math.max(minLength, Math.min(maxLength, minLength + STRING_PADDING));

        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }

    /** Well-known string formats worth honouring; null when unrecognised. */
    private String generateFormatted(String format, Random random) {
        return switch (format) {
            case "uuid" -> new UUID(random.nextLong(), random.nextLong()).toString();
            case "date-time" -> DateTimeFormatter.ISO_INSTANT.format(
                    EPOCH_BASE.plusSeconds(random.nextInt(365 * 24 * 60 * 60)));
            case "date" -> DateTimeFormatter.ISO_LOCAL_DATE.format(
                    EPOCH_BASE.plusSeconds(random.nextInt(365 * 24 * 60 * 60))
                            .atZone(ZoneOffset.UTC).toLocalDate());
            case "email" -> "user" + Math.abs(random.nextInt(100000)) + "@example.test";
            case "uri", "url" -> "https://example.test/" + Math.abs(random.nextInt(100000));
            case "hostname" -> "host" + Math.abs(random.nextInt(1000)) + ".example.test";
            default -> null;
        };
    }

    private long generateInteger(
            JsonNode schema, GenerationSpec spec, Random random, String path) {
        long minimum = bound(schema, "minimum", "exclusiveMinimum", 0, 1, path);
        long maximum = bound(schema, "maximum", "exclusiveMaximum", 1000, -1, path);
        if (maximum < minimum) {
            throw new SchemaException(path,
                    "maximum " + maximum + " is below minimum " + minimum);
        }
        long value = spec.strategy() == GenerationStrategy.BOUNDARY
                ? minimum
                : minimum + (long) (random.nextDouble() * (maximum - minimum));

        long multipleOf = schema.path("multipleOf").asLong(0);
        if (multipleOf > 0) {
            long aligned = value / multipleOf * multipleOf;
            if (aligned < minimum) {
                aligned += multipleOf;
            }
            if (aligned > maximum) {
                throw new SchemaException(path,
                        "no multiple of " + multipleOf + " lies between "
                                + minimum + " and " + maximum);
            }
            return aligned;
        }
        return value;
    }

    private BigDecimal generateNumber(
            JsonNode schema, GenerationSpec spec, Random random, String path) {
        double minimum = schema.path("minimum").asDouble(
                schema.path("exclusiveMinimum").asDouble(0));
        double maximum = schema.path("maximum").asDouble(
                schema.path("exclusiveMaximum").asDouble(1000));
        if (maximum < minimum) {
            throw new SchemaException(path,
                    "maximum " + maximum + " is below minimum " + minimum);
        }
        double value = spec.strategy() == GenerationStrategy.BOUNDARY
                ? minimum
                : minimum + random.nextDouble() * (maximum - minimum);
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * An inclusive integer bound.
     * <p>
     * OpenAPI 3.0 spells an exclusive bound as a boolean flag beside the
     * inclusive one; JSON Schema 2020 gives it a value of its own. Both are
     * normalised here to the nearest value the schema actually accepts.
     *
     * @param adjustment applied to an exclusive bound to make it inclusive
     */
    private long bound(
            JsonNode schema, String inclusiveField, String exclusiveField,
            long fallback, long adjustment, String path) {
        JsonNode exclusive = schema.get(exclusiveField);
        boolean exclusiveFlag = exclusive != null && exclusive.isBoolean()
                && exclusive.asBoolean();

        if (schema.hasNonNull(inclusiveField)) {
            long declared = schema.get(inclusiveField).asLong();
            return exclusiveFlag ? declared + adjustment : declared;
        }
        if (exclusive == null || exclusive.isNull() || exclusive.isBoolean()) {
            return fallback;
        }
        return exclusive.asLong() + adjustment;
    }

    /** Merge the branches of {@code allOf} into one schema, or null when absent. */
    private JsonNode composedSchema(JsonNode schema, String path) {
        JsonNode allOf = schema.get("allOf");
        if (allOf == null || !allOf.isArray() || allOf.isEmpty()) {
            return null;
        }
        ObjectNode merged = MAPPER.createObjectNode();
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        for (JsonNode branch : allOf) {
            JsonNode resolved = catalog.dereference(branch, path);
            if (resolved == null || !resolved.isObject()) {
                continue;
            }
            resolved.properties().forEach(field -> {
                switch (field.getKey()) {
                    case "properties" -> field.getValue().properties()
                            .forEach(property -> properties.set(
                                    property.getKey(), property.getValue()));
                    case "required" -> field.getValue().forEach(required::add);
                    case "allOf" -> { /* already flattened by this loop */ }
                    default -> merged.set(field.getKey(), field.getValue());
                }
            });
        }
        merged.set("properties", properties);
        merged.set("required", required);
        merged.put("type", "object");
        return merged;
    }

    private JsonNode firstArray(JsonNode schema, String... fields) {
        for (String field : fields) {
            JsonNode value = schema.get(field);
            if (value != null && value.isArray() && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Declared type, or the one implied by the schema's own structure. */
    static String typeOf(JsonNode schema, String path) {
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            return type.asText();
        }
        if (type != null && type.isArray() && !type.isEmpty()) {
            // A union of types: the first non-null member is generated.
            for (JsonNode member : type) {
                if (member.isTextual() && !"null".equals(member.asText())) {
                    return member.asText();
                }
            }
        }
        if (schema.has("properties")) return "object";
        if (schema.has("items")) return "array";
        throw new SchemaException(path,
                "schema declares no type and none can be inferred");
    }

    static List<String> requiredProperties(JsonNode schema) {
        List<String> required = new ArrayList<>();
        JsonNode declared = schema.get("required");
        if (declared != null && declared.isArray()) {
            declared.forEach(name -> required.add(name.asText()));
        }
        return required;
    }
}
