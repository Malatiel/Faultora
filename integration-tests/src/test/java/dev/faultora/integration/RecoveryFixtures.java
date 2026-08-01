package dev.faultora.integration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paths to the reference system's published contracts and scenarios.
 * <p>
 * The gate runs the documents shipped in {@code examples/payment-recovery} and
 * quoted in the documentation, not copies under test resources. A second copy
 * lets the two drift, and then the suite proves something nobody runs.
 */
final class RecoveryFixtures {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("examples").resolve("payment-recovery");

    private RecoveryFixtures() {
    }

    static Path scenario(String fileName) {
        return existing(ROOT.resolve("scenarios").resolve(fileName));
    }

    static Path openApi() {
        return existing(ROOT.resolve("openapi.yaml"));
    }

    static Path asyncApi() {
        return existing(ROOT.resolve("asyncapi.yaml"));
    }

    static Path observations() {
        return existing(ROOT.resolve("observations.yaml"));
    }

    private static Path existing(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IllegalStateException("Reference fixture is missing: " + absolute);
        }
        return absolute;
    }
}
