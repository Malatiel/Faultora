package dev.faultora.integration;

import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ParseResult;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the documentation shows a newcomer to copy is what Faultora accepts.
 * <p>
 * Two cold-start trials, one through the CLI and one through MCP, both met a
 * reference that said a scenario must be {@code faultora.dev/v1alpha1} while
 * every example and the generator wrote {@code v1}. Each complete scenario the
 * README or a guide shows must parse and validate with neither an error nor a
 * warning, and the field reference must name the frozen version.
 */
class DocumentedScenariosTest {
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final Pattern YAML_BLOCK = Pattern.compile("```ya?ml\\R(.*?)```", Pattern.DOTALL);
    /** A whole document, not a fragment showing one field: it has every required top-level section. */
    private static final List<Pattern> COMPLETE_SCENARIO = List.of(
            Pattern.compile("(?m)^apiVersion:\\s*faultora\\.dev/\\S+"), Pattern.compile("(?m)^kind:\\s*Scenario\\s*$"),
            Pattern.compile("(?m)^metadata:"), Pattern.compile("(?m)^execute:"));

    @Test
    void everyCompleteScenarioTheDocumentationShowsIsAcceptedWithoutWarnings() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Path guide : guides()) {
            Matcher block = YAML_BLOCK.matcher(Files.readString(guide, StandardCharsets.UTF_8));
            int index = 0;
            while (block.find()) {
                index++;
                String yaml = block.group(1);
                if (!COMPLETE_SCENARIO.stream().allMatch(section -> section.matcher(yaml).find())) {
                    continue;
                }
                String where = ROOT.relativize(guide) + " block " + index;
                ParseResult<ScenarioDocument> parsed = new ScenarioParser().parse(yaml);
                assertThat(messages(parsed.diagnostics())).as("parse of %s", where).isEmpty();
                ParseResult<ScenarioDocument> validated = new ScenarioValidator().validate(parsed.document());
                assertThat(messages(validated.diagnostics())).as("validation of %s", where).isEmpty();
                checked.add(where);
            }
        }
        // A pattern that silently matched nothing would make this test pass for no reason.
        assertThat(checked).as("complete scenarios found in the documentation").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theFieldReferenceNamesTheFrozenVersion() throws Exception {
        String reference = Files.readString(ROOT.resolve("docs/SCENARIO_REFERENCE.md"), StandardCharsets.UTF_8);
        String row = reference.lines().filter(line -> line.startsWith("| `apiVersion` |")).findFirst().orElseThrow();
        assertThat(row).contains("`faultora.dev/v1`").doesNotContain("Must be `faultora.dev/v1alpha1`");
    }

    private static List<Path> guides() throws Exception {
        List<Path> guides = new ArrayList<>(List.of(ROOT.resolve("README.md")));
        try (Stream<Path> docs = Files.list(ROOT.resolve("docs"))) {
            docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(guides::add);
        }
        return guides;
    }

    private static List<String> messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> diagnostic.severity() + " " + diagnostic.path()
                + ": " + diagnostic.message()).toList();
    }
}
