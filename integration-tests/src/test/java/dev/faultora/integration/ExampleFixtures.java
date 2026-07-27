package dev.faultora.integration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paths to the published example fixtures.
 * <p>
 * The end-to-end suite runs the very scenarios and OpenAPI document shipped in
 * {@code examples/payment-service} and quoted in the documentation. Keeping a
 * second copy under test resources let the two drift apart, which meant CI was
 * proving something users never run.
 */
final class ExampleFixtures {

    private static final Path EXAMPLE_ROOT = Path.of("..", "examples", "payment-service");

    private ExampleFixtures() {
    }

    /** Absolute path of a published example scenario. */
    static Path scenario(String fileName) {
        return existing(EXAMPLE_ROOT.resolve("scenarios").resolve(fileName));
    }

    /** Absolute path of the example API's OpenAPI document. */
    static Path openApi() {
        return existing(EXAMPLE_ROOT.resolve("openapi.yaml"));
    }

    private static Path existing(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IllegalStateException("Example fixture is missing: " + absolute);
        }
        return absolute;
    }
}
