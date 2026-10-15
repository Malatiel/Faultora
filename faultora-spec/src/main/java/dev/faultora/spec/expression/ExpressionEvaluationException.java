package dev.faultora.spec.expression;

/**
 * Thrown when an expression cannot be evaluated.
 * Retains the failing expression internally. Use diagnostic() for export.
 */
public class ExpressionEvaluationException extends RuntimeException {

    private final String expression;
    private final String inputPath;

    public ExpressionEvaluationException(String message, String expression) {
        this(message, expression, null, null);
    }

    public ExpressionEvaluationException(String message, String expression, Throwable cause) {
        this(message, expression, cause, null);
    }

    ExpressionEvaluationException(String message, String expression, Throwable cause, String inputPath) {
        super(message, cause);
        this.expression = expression;
        this.inputPath = inputPath;
    }

    /** A public input location, without the expression, its values, or provider exception text. */
    public String diagnostic() {
        return inputPath != null && inputPath.length() <= 256 && inputPath.matches("[A-Za-z_][A-Za-z0-9_.\\[\\]-]*")
                ? "Expression could not be resolved at input '" + inputPath + "'"
                : "Expression could not be resolved";
    }

    /**
     * The expression that failed evaluation.
     */
    public String expression() {
        return expression;
    }
}
