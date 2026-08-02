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
     * Both refusals exist because the alternative is worse than a failure. A
     * value that resolved to nothing would let the assertion provider decide
     * what null means, and the answer would not be the author's. A value
     * derived from a secret would be compared, and then written into the
     * assertion's message — which reaches the journal, the console, and the
     * HTML report. An assertion is not a place to move a secret to.
     */
    String refusal(
            Map<String, Object> declared,
            Map<String, Object> resolved,
            ExpressionContext context
    ) {
        String secret = firstSecretReference(declared, context);
        if (secret != null) {
            return "Parameter '" + secret + "' reads a secret. An assertion compares by "
                    + "writing what it compared into its message, which the journal and "
                    + "the report keep, so a secret cannot be one of its parameters";
        }
        return firstReferenceResolvingToNothing(null, declared, resolved);
    }

    /** The name of the first parameter whose expression names a secret. */
    private String firstSecretReference(
            Map<String, Object> declared, ExpressionContext context) {
        for (Map.Entry<String, Object> parameter : declared.entrySet()) {
            if (namesASecret(parameter.getValue(), context)) {
                return parameter.getKey();
            }
        }
        return null;
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
     * The first template that resolved to null, described by where it sits.
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
            String path, Object declared, Object resolved) {
        return switch (declared) {
            case String text when text.contains("{{") && resolved == null ->
                    "Parameter '" + path + "' reads " + text + ", which is null. An "
                            + "assertion compares against a value, and null is the "
                            + "absence of one — ask for it with exists: false rather "
                            + "than comparing to it";
            case Map<?, ?> map -> {
                Map<?, ?> resolvedMap = resolved instanceof Map<?, ?> other ? other : Map.of();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String nestedPath = path == null
                            ? String.valueOf(entry.getKey())
                            : path + "." + entry.getKey();
                    String refusal = firstReferenceResolvingToNothing(
                            nestedPath, entry.getValue(), resolvedMap.get(entry.getKey()));
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
                            (path == null ? "" : path) + "[" + index + "]",
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
