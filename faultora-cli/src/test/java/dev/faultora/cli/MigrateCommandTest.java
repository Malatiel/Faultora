package dev.faultora.cli;

import dev.faultora.spec.model.ApiVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving a document to the frozen version.
 * <p>
 * The change is one token, and everything worth testing here is about what the
 * tool does <em>not</em> do: it does not rewrite files nobody asked it to, it
 * does not reformat the document around the line it changes, it does not touch
 * documents that are not Faultora's, and it cannot leave a half-written one.
 */
class MigrateCommandTest {

    /** Comments, spacing and key order that a YAML round trip would flatten. */
    private static final String SCENARIO = """
            # Written by somebody who cared about the comments.
            apiVersion: faultora.dev/v1alpha1
            kind: Scenario

            metadata:
              name: keeps-its-shape        # trailing comment
              labels:
                team: payments

            execute:
              - id: pause
                type: wait
                timeout: 10ms
            """;

    @TempDir
    Path directory;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int migrate(String... args) {
        return new MigrateCommand(new PrintWriter(out, true), new PrintWriter(err, true))
                .execute(List.of(args));
    }

    private Path scenarioNamed(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent() == null ? directory : file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void itReportsWithoutChangingAnything() throws Exception {
        Path scenario = scenarioNamed("scenario.yaml", SCENARIO);

        assertThat(migrate(scenario.toString())).isEqualTo(FaultoraCli.EXIT_PASS);

        assertThat(out.toString()).contains("would move", ApiVersions.CURRENT);
        assertThat(Files.readString(scenario))
                .as("a migrator that rewrote files on being run is one somebody "
                        + "runs once by accident")
                .isEqualTo(SCENARIO);
    }

    @Test
    void itChangesOneTokenAndLeavesTheRestAlone() throws Exception {
        // The reason this edits text rather than round-tripping YAML: a correct
        // document with the comments dropped and the keys reordered is a diff
        // nobody can review, for a change of one word.
        Path scenario = scenarioNamed("scenario.yaml", SCENARIO);

        migrate(scenario.toString(), "--write");

        assertThat(Files.readString(scenario))
                .isEqualTo(SCENARIO.replace("faultora.dev/v1alpha1", ApiVersions.CURRENT));
    }

    @Test
    void runningItTwiceDoesNothingTheSecondTime() throws Exception {
        Path scenario = scenarioNamed("scenario.yaml", SCENARIO);
        migrate(scenario.toString(), "--write");
        String afterOnce = Files.readString(scenario);

        out.getBuffer().setLength(0);
        migrate(scenario.toString(), "--write");

        assertThat(Files.readString(scenario)).isEqualTo(afterOnce);
        assertThat(out.toString()).contains("Nothing to migrate");
    }

    @Test
    void itFindsDocumentsUnderADirectoryAndIgnoresWhatIsNotOne() throws Exception {
        scenarioNamed("scenarios/one.yaml", SCENARIO);
        scenarioNamed("scenarios/two.yml", SCENARIO);
        // An OpenAPI document in the same tree: it has no Faultora apiVersion,
        // and touching it would be the tool exceeding what it was asked.
        Path openApi = scenarioNamed("openapi.yaml", """
                openapi: 3.0.3
                info:
                  title: Payments
                  version: 1.0.0
                paths: {}
                """);
        String describedApi = Files.readString(openApi);

        migrate(directory.toString(), "--write");

        assertThat(Files.readString(directory.resolve("scenarios/one.yaml")))
                .contains(ApiVersions.CURRENT);
        assertThat(Files.readString(directory.resolve("scenarios/two.yml")))
                .contains(ApiVersions.CURRENT);
        assertThat(Files.readString(openApi)).isEqualTo(describedApi);
        assertThat(out.toString()).contains("Migrated 2 document(s)");
    }

    @Test
    void aVersionNamedInsideTheDocumentIsNotTheVersionOfTheDocument() throws Exception {
        // The pattern is anchored to the start of a line for this reason: a
        // description that mentions the old version is prose, not a declaration.
        Path scenario = scenarioNamed("scenario.yaml", """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: mentions-a-version
                  description: "Was written for apiVersion: faultora.dev/v1alpha1"
                execute:
                  - id: pause
                    type: wait
                    timeout: 10ms
                """);

        migrate(scenario.toString(), "--write");

        String after = Files.readString(scenario);
        assertThat(after).startsWith("apiVersion: " + ApiVersions.CURRENT);
        assertThat(after)
                .as("the sentence is left as its author wrote it")
                .contains("description: \"Was written for apiVersion: faultora.dev/v1alpha1\"");
    }

    @Test
    void aQuotedVersionIsStillTheVersion() throws Exception {
        // YAML lets a scalar be quoted, and the parser reads the two the same
        // way — so a migrator that took the quotes for part of the version
        // would skip the file, report that there was nothing to do, and leave
        // every run of that scenario warning about a version it just said was
        // current. Both quoting styles, because a tool is used the way its
        // author's editor formats YAML.
        Path single = scenarioNamed("single.yaml",
                SCENARIO.replace("apiVersion: faultora.dev/v1alpha1",
                        "apiVersion: 'faultora.dev/v1alpha1'"));
        Path doubled = scenarioNamed("double.yaml",
                SCENARIO.replace("apiVersion: faultora.dev/v1alpha1",
                        "apiVersion: \"faultora.dev/v1alpha1\""));

        migrate(single.toString(), doubled.toString(), "--write");

        assertThat(Files.readString(single))
                .as("the quotes are the author's, and stay")
                .contains("apiVersion: '" + ApiVersions.CURRENT + "'");
        assertThat(Files.readString(doubled))
                .contains("apiVersion: \"" + ApiVersions.CURRENT + "\"");
        assertThat(out.toString()).contains("Migrated 2 document(s)");
    }

    @Test
    void anApiVersionFromSomewhereElseIsNotOurs() throws Exception {
        // Kubernetes manifests have an apiVersion too, and this command is run
        // over whole repositories. Counting them as ours would also make the
        // summary claim they declare a Faultora version.
        scenarioNamed("deployment.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: not-a-scenario
                """);
        String before = Files.readString(directory.resolve("deployment.yaml"));

        migrate(directory.toString(), "--write");

        assertThat(Files.readString(directory.resolve("deployment.yaml"))).isEqualTo(before);
        assertThat(out.toString())
                .as("nothing here is ours, and the summary says so rather than "
                        + "counting somebody else's manifest")
                .contains("Nothing to migrate: 0 document(s)");
    }

    @Test
    void itMovesADocumentWithoutJudgingIt() throws Exception {
        // A scenario missing its steps is invalid before and after, and saying
        // so is `faultora validate`'s job. This command moves a version; a
        // migrator that also refused documents would be two tools, one of them
        // silent about why it did nothing.
        Path incomplete = scenarioNamed("incomplete.yaml", """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata:
                  name: has-no-steps
                """);

        migrate(incomplete.toString(), "--write");

        assertThat(Files.readString(incomplete)).contains(ApiVersions.CURRENT);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void itLeavesNoHalfWrittenFileBehind() throws Exception {
        // Written to one side and moved into place, so a tool run over a whole
        // repository cannot leave a truncated scenario where a working one was.
        scenarioNamed("scenario.yaml", SCENARIO);

        migrate(directory.toString(), "--write");

        try (var left = Files.list(directory)) {
            assertThat(left.map(path -> path.getFileName().toString()))
                    .as("nothing but the documents that were there")
                    .containsExactly("scenario.yaml");
        }
    }

    @Test
    void namingNothingIsAMistakeRatherThanAWholeDiskRewritten() {
        assertThatThrownBy(() -> migrate("--write"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("name a file or a directory");
    }
}
