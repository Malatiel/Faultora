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

    private static final Path PROJECT_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();
    private static final Path EXAMPLE_ROOT =
            PROJECT_ROOT.resolve("examples").resolve("payment-service");
    private static final Path WORKER_ROOT =
            PROJECT_ROOT.resolve("examples").resolve("payment-worker");

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

    /** Absolute path of a scenario published with the example event worker. */
    static Path workerScenario(String fileName) {
        return existing(WORKER_ROOT.resolve("scenarios").resolve(fileName));
    }

    /** Absolute path of the example worker's AsyncAPI document. */
    static Path asyncApi() {
        return existing(WORKER_ROOT.resolve("asyncapi.yaml"));
    }

    private static Path existing(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IllegalStateException("Example fixture is missing: " + absolute);
        }
        return absolute;
    }
}
