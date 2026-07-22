package dev.faultora.spec.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Immutable evaluation context for expressions.
 * Contains inputs, environment, step outputs, and run metadata.
 * Expressions are evaluated against this context as a JSON tree.
 */
public final class ExpressionContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode contextTree;
    private final Set<String> secretKeys;

    private ExpressionContext(JsonNode contextTree, Set<String> secretKeys) {
        this.contextTree = contextTree;
        this.secretKeys = secretKeys;
    }

    /**
     * Get the JSON tree representing the full evaluation context.
     */
    public JsonNode tree() {
        return contextTree;
    }

    /**
     * Check if a key path originates from a secret resolver.
     *
     * @param path the dotted path to check (e.g. "secrets.api-key")
     * @return true if this path or any prefix is a secret key
     */
    public boolean isSecret(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String secretKey : secretKeys) {
            if (lower.startsWith(secretKey.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the set of secret key prefixes.
     */
    public Set<String> secretKeys() {
        return secretKeys;
    }

    /**
     * Create a builder for constructing an ExpressionContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ExpressionContext.
     */
    public static final class Builder {
        private final ObjectNode root = MAPPER.createObjectNode();
        private ObjectNode inputs = MAPPER.createObjectNode();
        private ObjectNode environment = MAPPER.createObjectNode();
        private ObjectNode stepOutputs = MAPPER.createObjectNode();
        private ObjectNode runMetadata = MAPPER.createObjectNode();
        private ObjectNode secrets = MAPPER.createObjectNode();
        private java.util.Set<String> secretKeys = new java.util.HashSet<>();

        public Builder inputs(Map<String, Object> inputs) {
            this.inputs = MAPPER.valueToTree(inputs);
            return this;
        }

        public Builder inputs(JsonNode inputs) {
            this.inputs = (ObjectNode) inputs;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = MAPPER.valueToTree(environment);
            return this;
        }

        public Builder stepOutput(String stepId, JsonNode output) {
            this.stepOutputs.set(stepId, output);
            return this;
        }

        public Builder runMetadata(Map<String, Object> metadata) {
            this.runMetadata = MAPPER.valueToTree(metadata);
            return this;
        }

        public Builder secret(String key, String redactedValue) {
            this.secrets.put(key, redactedValue);
            this.secretKeys.add("secrets." + key);
            return this;
        }

        public Builder secretKeys(Set<String> keys) {
            this.secretKeys.addAll(keys);
            return this;
        }

        public ExpressionContext build() {
            root.set("inputs", inputs);
            root.set("env", environment);
            root.set("steps", stepOutputs);
            root.set("run", runMetadata);
            root.set("secrets", secrets);
            return new ExpressionContext(root, secretKeys);
        }
    }
}
