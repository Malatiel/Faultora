package dev.faultora.engine.exec;

import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spec.expression.ExpressionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parameters an assertion is evaluated with.
 * <p>
 * Two places produce them — an assertion node, and the {@code until} conditions
 * of a polling block — and both need the same three things: the templates
 * resolved, a value that resolved to nothing refused, and a value derived from
 * a secret refused. Having that in one class is why the polling block cannot
 * quietly lose a guard the assertion section has, which is exactly what
 * happened when the two resolved their parameters separately.
 */
final class AssertionParameters {

    /** One {@code {{expression}}} inside a parameter value. */
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{(.+?)}}");

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    /** Resolve every template in the parameters, at any depth. */
    Map<String, Object> resolve(Map<String, Object> declared, ExpressionContext context) {
        return evaluator.resolveInputs(declared, context);
    }

    /**
     * Why these parameters cannot be compared with, or null when they can.
     * <p>
     * A template resolving to null cannot establish a comparison. Secret
     * references remain outside assertion capabilities even though the export
     * boundary now also withholds provider comparison details.
     */
    String refusal(
            Map<String, Object> declared,
            Map<String, Object> resolved,
            ExpressionContext context
    ) {
        if (namesASecret(declared, context)) {
            return "An assertion parameter reads a secret; secret references are not allowed in assertions";
        }
        return firstReferenceResolvingToNothing(declared, resolved);
    }

    private boolean namesASecret(Object value, ExpressionContext context) {
        return switch (value) {
            case String text -> {
                Matcher template = TEMPLATE.matcher(text);
                while (template.find()) {
                    if (context.isSecret(template.group(1).trim())) {
                        yield true;
                    }
                }
                yield false;
            }
            case Map<?, ?> map -> map.values().stream()
                    .anyMatch(nested -> namesASecret(nested, context));
            case List<?> list -> list.stream()
                    .anyMatch(item -> namesASecret(item, context));
            case null, default -> false;
        };
    }

    /**
     * A refusal when a template resolved to null, without repeating its expression.
     * <p>
     * A template naming something absent no longer reaches here: it fails
     * during resolution, in both step inputs and assertion parameters
     * (ADR-018). What is left is the narrower case — an expression that named
     * a real field whose value is null — and an assertion is the one place
     * where that is still refused. Comparing against null is comparing against
     * the absence of a value, and every provider would have to decide for
     * itself what that means; {@code exists: false} is how a scenario asks the
     * question it was reaching for.
     * <p>
     * Declared and resolved are walked together, so a template nested inside a
     * map or a list is checked like a top-level one. A parameter written as a
     * literal null is left alone: it says null on purpose.
     */
    private String firstReferenceResolvingToNothing(
            Object declared, Object resolved) {
        return switch (declared) {
            case String text when text.contains("{{") && resolved == null ->
                    "An assertion parameter reads a value which is null; ask for it with exists: false";
            case Map<?, ?> map -> {
                Map<?, ?> resolvedMap = resolved instanceof Map<?, ?> other ? other : Map.of();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String refusal = firstReferenceResolvingToNothing(
                            entry.getValue(), resolvedMap.get(entry.getKey()));
                    if (refusal != null) {
                        yield refusal;
                    }
                }
                yield null;
            }
            case List<?> list -> {
                List<?> resolvedList = resolved instanceof List<?> other ? other : List.of();
                for (int index = 0; index < list.size(); index++) {
                    Object resolvedItem = index < resolvedList.size()
                            ? resolvedList.get(index) : null;
                    String refusal = firstReferenceResolvingToNothing(
                            list.get(index), resolvedItem);
                    if (refusal != null) {
                        yield refusal;
                    }
                }
                yield null;
            }
            case null, default -> null;
        };
    }
}
