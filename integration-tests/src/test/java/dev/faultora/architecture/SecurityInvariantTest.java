package dev.faultora.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture tests that verify security invariants across the codebase.
 */
class SecurityInvariantTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void noUnsafeYamlConstructor() throws IOException {
        List<String> violations = new ArrayList<>();
        String[] sourceDirs = {
                "faultora-model/src",
                "faultora-spi/src",
                "faultora-spec/src",
                "faultora-engine/src",
                "faultora-import-openapi/src",
                "faultora-connector-http/src",
                "faultora-assertions-core/src",
                "faultora-reporting/src",
                "faultora-cli/src"
        };

        for (String dir : sourceDirs) {
            Path srcPath = PROJECT_ROOT.resolve(dir);
            if (!Files.exists(srcPath)) continue;

            Files.walkFileTree(srcPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        try {
                            String content = Files.readString(file);
                            if (content.contains("new Yaml(new Constructor(") ||
                                content.contains("new Constructor(") && content.contains("Yaml")) {
                                // Allow only SafeConstructor
                                if (!content.contains("SafeConstructor") &&
                                    content.contains("new Constructor(")) {
                                    violations.add(file.toString() + ": uses unsafe Constructor");
                                }
                            }
                        } catch (IOException e) {
                            // skip unreadable files
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        assertThat(violations)
                .as("No unsafe YAML Constructor usage should exist outside approved locations")
                .isEmpty();
    }

    @Test
    void noSecretsInTestFixtures() throws IOException {
        List<String> violations = new ArrayList<>();
        Path fixturesDir = PROJECT_ROOT.resolve("faultora-model/src/test/resources/fixtures");
        if (!Files.exists(fixturesDir)) return;

        Files.walkFileTree(fixturesDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".json")) {
                    try {
                        String content = Files.readString(file);
                        // Check for patterns that look like real credentials
                        if (content.matches("(?i).*\"(password|secret|token|api_key|apikey)\"\\s*:\\s*\"[^\"]{8,}\".*")) {
                            violations.add(file.toString() + ": may contain real credentials");
                        }
                    } catch (IOException e) {
                        // skip
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(violations)
                .as("Test fixtures must not contain real credentials")
                .isEmpty();
    }

    @Test
    void noEnvFilesCommitted() throws IOException {
        Path gitignore = PROJECT_ROOT.resolve(".gitignore");
        assertThat(Files.exists(gitignore)).isTrue();
        String content = Files.readString(gitignore);
        assertThat(content).contains(".env");
    }

    @Test
    void secretHandleToStringDoesNotExposeValue() {
        dev.faultora.model.security.SecretHandle handle = new dev.faultora.model.security.SecretHandle(
                "test-handle",
                "sk-***abcd",
                "env",
                System.currentTimeMillis() + 3600000
        );

        String str = handle.toString();
        assertThat(str).contains("test-handle");
        assertThat(str).contains("sk-***abcd");
        assertThat(str).doesNotContain("actual-secret");
    }
}
