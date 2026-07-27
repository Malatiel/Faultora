package dev.faultora.schema;

/**
 * How one value is to be generated.
 *
 * @param strategy       relation between the value and its constraints
 * @param preferExamples whether an {@code example} declared in the schema is
 *                       used verbatim instead of a generated value; an authored
 *                       example is usually more meaningful than anything a
 *                       generator can invent
 */
public record GenerationSpec(GenerationStrategy strategy, boolean preferExamples) {

    public static final GenerationSpec DEFAULT = new GenerationSpec(GenerationStrategy.VALID, true);

    public GenerationSpec {
        if (strategy == null) {
            strategy = GenerationStrategy.VALID;
        }
    }
}
