package dev.faultora.spec.expression;

/**
 * Thrown when an expression cannot be evaluated.
 * Contains the failing expression for diagnostic output.
 */
public class ExpressionEvaluationException extends RuntimeException {

    private final String expression;

    public ExpressionEvaluationException(String message, String expression) {
        super(message);
        this.expression = expression;
    }

    public ExpressionEvaluationException(String message, String expression, Throwable cause) {
        super(message, cause);
        this.expression = expression;
    }

    /**
     * The expression that failed evaluation.
     */
    public String expression() {
        return expression;
    }
}
