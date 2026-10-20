package dev.faultora.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release version is set once, as {@code revision} in the parent POM.
 * Documentation, the image recipe and the deployment examples still name it
 * for people to copy, so this holds every such mention to that one value: a
 * release that changes the property and forgets a file fails here instead of
 * shipping instructions for the previous version.
 */
class ReleaseVersionConsistencyTest {

    private static final String SEMVER = "(\\d+\\.\\d+\\.\\d+)";

    /** Phrases that name the version being released, never a historical one. */
    private static final List<Pattern> RELEASE_MENTIONS = List.of(
            Pattern.compile("faultora-" + SEMVER + "\\.jar"),
            Pattern.compile("faultora/runner:" + SEMVER),
            Pattern.compile("ARG VERSION=" + SEMVER),
            Pattern.compile("VERSION=\"\\$\\{1:-" + SEMVER + "}\""),
            Pattern.compile("FAULTORA_VERSION=" + SEMVER),
            Pattern.compile("Version " + SEMVER + " is a runnable"),
            Pattern.compile("## What " + SEMVER + " includes"),
            Pattern.compile("implemented by Faultora " + SEMVER),
            Pattern.compile("available in " + SEMVER + "\\."),
            Pattern.compile("Reserved; " + SEMVER + " reports"),
            Pattern.compile("## 2\\. Where " + SEMVER + " stands"));

    private static final List<String> FILES = List.of(
            "README.md", "examples/README.md", "Dockerfile", "deploy/docker-compose.yml", "deploy/offline-bundle.sh",
            "docs/CLI_DIAGNOSTICS.md", "docs/MCP.md", "docs/RELEASE_PLAN.md",
            "docs/SCENARIO_REFERENCE.md");

    @Test
    void everyReleaseMentionNamesTheProjectVersion() throws IOException {
        String version = System.getProperty("faultora.version");
        Path root = Path.of(System.getProperty("faultora.projectRoot"));
        assertThat(version).as("project version passed by the build").matches(SEMVER);

        List<String> mismatches = new ArrayList<>();
        int mentions = 0;
        for (String file : FILES) {
            String text = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
            for (Pattern pattern : RELEASE_MENTIONS) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    mentions++;
                    if (!matcher.group(1).equals(version)) {
                        mismatches.add(file + ": '" + matcher.group() + "'");
                    }
                }
            }
        }

        assertThat(mentions).as("the patterns still find the release mentions").isGreaterThan(20);
        assertThat(mismatches)
                .as("these name a version other than %s, the revision in the parent POM", version)
                .isEmpty();
    }
}
