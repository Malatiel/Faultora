package dev.faultora.spec.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates expressions against an ExpressionContext.
 * Uses a simple dotted-path resolver for property access (supports hyphenated keys),
 * and delegates to JMESPath only for function calls (type, length, etc.).
 * Expressions are read-only and side-effect free.
 * Secret-derived values are redacted in diagnostic output.
 */
public final class ExpressionEvaluator {

    /**
     * Pattern matching template expressions: {{expression}}
     */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    /**
     * Pattern for splitting dotted paths. Segments may be:
     * - plain identifiers: foo, bar-baz
     * - quoted identifiers: "my-step"
     */
    private static final Pattern PATH_SEGMENT = Pattern.compile("\"([^\"]+)\"|([^.]+)");

    private final JmesPath<JsonNode> jmespath;

    public ExpressionEvaluator() {
        this.jmespath = new JacksonRuntime();
    }

    /**
     * Evaluate an expression against the given context.
     * Supports dotted path access (inputs.name, steps.create-payment.id)
     * and JMESPath function calls (type(inputs.name), length(inputs.name)).
     *
     * <p>
     * Never returns Java null. A path that matches nothing gives a
     * {@link MissingNode} and a path whose value is null gives a null node,
     * because a caller that has to tell an author's mistake from a document's
     * null cannot do it against one answer used for both.
     *
     * @param expression the expression string
     * @param context    the evaluation context
     * @return the evaluated node, missing when the expression matches nothing
     * @throws ExpressionEvaluationException if evaluation fails
     */
    public JsonNode evaluate(String expression, ExpressionContext context) {
        if (expression == null || expression.isBlank()) {
            return MissingNode.getInstance();
        }

        String trimmed = expression.trim();

        try {
            // Check if it's a function call (contains parentheses)
            if (trimmed.contains("(")) {
                return evaluateJmespath(trimmed, context);
            }
            // Otherwise use dotted-path resolution (supports hyphens)
            return resolvePath(trimmed, context.tree());
        } catch (ExpressionEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new ExpressionEvaluationException(
                    "Failed to evaluate expression: " + expression + ". Cause: " + e.getMessage(),
                    expression,
                    e
            );
        }
    }

    /**
     * Resolve a template string containing {{expression}} placeholders.
     * If the entire string is a single {{expression}}, returns the raw evaluated value
     * (preserving type). Otherwise, performs string interpolation.
     *
     * @param template the template string (e.g. "Hello {{inputs.name}}")
     * @param context  the evaluation context
     * @return the resolved value
     */
    public Object resolveTemplate(String template, ExpressionContext context) {
        if (template == null || template.isBlank()) {
            return template;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);

        // Check if the entire string is a single expression (preserve type)
        if (matcher.matches()) {
            String expr = matcher.group(1).trim();
            JsonNode result = evaluate(expr, context);
            return jsonNodeToValue(result);
        }

        // String interpolation: replace each {{expression}} with its string value
        StringBuffer sb = new StringBuffer();
        matcher.reset();
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            JsonNode result = evaluate(expr, context);
            String replacement = result == null || result.isNull() || result.isMissingNode()
                    ? "" : result.asText();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolve all expressions in a map of input values.
     * Keys are input names; values may contain template expressions, including
     * inside nested maps and lists (e.g. {@code body} or {@code headers}).
     *
     * @param inputs  the input map with possible template values
     * @param context the evaluation context
     * @return a new map with resolved values
     */
    public java.util.Map<String, Object> resolveInputs(
            java.util.Map<String, Object> inputs,
            ExpressionContext context
    ) {
        if (inputs == null) return java.util.Map.of();

        var resolved = new java.util.LinkedHashMap<String, Object>();
        for (var entry : inputs.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), context));
        }
        return resolved;
    }

    private Object resolveValue(Object value, ExpressionContext context) {
        if (value instanceof String s) {
            return resolveTemplate(s, context);
        }
        if (value instanceof java.util.Map<?, ?> map) {
            var resolved = new java.util.LinkedHashMap<Object, Object>();
            for (var entry : map.entrySet()) {
                resolved.put(entry.getKey(), resolveValue(entry.getValue(), context));
            }
            return resolved;
        }
        if (value instanceof java.util.List<?> list) {
            var resolved = new java.util.ArrayList<Object>(list.size());
            for (Object item : list) {
                resolved.add(resolveValue(item, context));
            }
            return resolved;
        }
        return value;
    }

    /**
     * Create a diagnostic-safe representation of an expression result,
     * redacting any values derived from secret resolvers.
     *
     * @param expression the expression that was evaluated
     * @param value      the evaluated value
     * @param context    the evaluation context
     * @return a string safe for diagnostic output
     */
    public String toDiagnosticString(String expression, Object value, ExpressionContext context) {
        if (context.isSecret(expression)) {
            return "[REDACTED]";
        }
        if (value == null) return "null";
        if (value instanceof JsonNode node) {
            return node.isTextual() ? node.asText() : node.toString();
        }
        return value.toString();
    }

    /**
     * Resolve a dotted path against a JSON tree.
     * <p>
     * Supports hyphenated identifiers, quoted segments, and indexing a list by
     * position: {@code steps.read.messages.0.payload.paymentId} reads the first
     * message a step observed.
     * <p>
     * An object is addressed by key and a list by position, including when the
     * key looks like a number — a key is a name and an index is a place, and a
     * document with a {@code "0"} field means the field. A quoted segment is
     * always a key, so {@code "0"} never indexes.
     *
     * @param path the dotted path (e.g. "steps.create-payment.id")
     * @param tree the JSON tree to navigate
     * @return the resolved node, {@link MissingNode} when the path matches
     *         nothing, and a null node when it matches a value that is null
     */
    JsonNode resolvePath(String path, JsonNode tree) {
        if (path == null || path.isBlank() || tree == null) {
            return MissingNode.getInstance();
        }

        JsonNode current = tree;
        Matcher matcher = PATH_SEGMENT.matcher(path);

        while (matcher.find()) {
            boolean quoted = matcher.group(1) != null;
            String segment = quoted ? matcher.group(1) : matcher.group(2);
            current = descend(current, segment, quoted);
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    /** One step along a path: a key of an object, or a place in a list. */
    private static JsonNode descend(JsonNode current, String segment, boolean quoted) {
        if (current == null || current.isMissingNode() || current.isNull()) {
            // Nothing to descend into. A path through a null is not a null
            // value at the end of a path — it is a path that matches nothing.
            return MissingNode.getInstance();
        }
        if (current.isObject()) {
            JsonNode next = current.get(segment);
            return next == null ? MissingNode.getInstance() : next;
        }
        if (current.isArray() && !quoted && isIndex(segment)) {
            JsonNode next = current.get(Integer.parseInt(segment));
            return next == null ? MissingNode.getInstance() : next;
        }
        return MissingNode.getInstance();
    }

    /** Whether a segment names a position rather than a key. */
    private static boolean isIndex(String segment) {
        if (segment.isEmpty() || segment.length() > 9) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            if (!Character.isDigit(segment.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate using JMESPath for function calls.
     */
    private JsonNode evaluateJmespath(String expression, ExpressionContext context) {
        try {
            io.burt.jmespath.Expression<JsonNode> compiled = jmespath.compile(expression);
            JsonNode result = compiled.search(context.tree());
            // JMESPath answers a query that matched nothing with null, so a
            // null from it is "no match" rather than a document's own null.
            return result == null || result.isNull()
                    ? MissingNode.getInstance() : result;
        } catch (Exception e) {
            throw new ExpressionEvaluationException(
                    "Failed to evaluate JMESPath expression: " + expression + ". Cause: " + e.getMessage(),
                    expression,
                    e
            );
        }
    }

    /**
     * Convert a JsonNode to a plain Java value.
     */
    private Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        // For objects and arrays, return the node itself
        return node;
    }
}
