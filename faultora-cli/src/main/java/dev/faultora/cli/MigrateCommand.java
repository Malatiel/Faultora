package dev.faultora.cli;

import dev.faultora.spec.model.ApiVersions;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code faultora migrate} — move documents to the current API version.
 * <p>
 * What it changes is one token. {@code faultora.dev/v1} froze the semantics
 * {@code v1alpha1} already had, so a document needs nothing but a new version
 * line — and saying that plainly is more useful than a tool that implies
 * otherwise.
 * <p>
 * Three decisions, each about not surprising anybody:
 * <ul>
 *   <li><b>It reports by default and writes when asked.</b> A migrator that
 *       rewrote files on being run is one somebody runs once by accident.</li>
 *   <li><b>It edits text, not YAML.</b> A round-trip through a parser would
 *       return a correct document with the comments dropped, the key order
 *       changed and the block scalars reflowed — a diff nobody can review, for
 *       a change of one word.</li>
 *   <li><b>It writes atomically.</b> A half-written scenario is worse than an
 *       unmigrated one, and a tool run over somebody's whole repository should
 *       not be able to leave one behind.</li>
 * </ul>
 * Running it twice does nothing the second time, which is what makes it safe
 * to put in a script.
 */
final class MigrateCommand implements Command {

    /**
     * The version line, wherever it sits.
     * <p>
     * Anchored to the start of a line so a version named inside a description
     * or a comment is left alone.
     */
    private static final Pattern API_VERSION_LINE =
            Pattern.compile("^(\\s*apiVersion\\s*:\\s*)([^\\s#]+)(.*)$", Pattern.MULTILINE);

    private final PrintWriter out;
    private final PrintWriter err;

    MigrateCommand(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public int execute(List<String> args) {
        List<Path> paths = new ArrayList<>();
        boolean write = false;

        Iterator<String> remaining = args.iterator();
        while (remaining.hasNext()) {
            String option = remaining.next();
            switch (option) {
                case "--help", "-h" -> {
                    printUsage();
                    return FaultoraCli.EXIT_PASS;
                }
                case "--write" -> write = true;
                case "--scenario", "--path" -> paths.add(Path.of(value(remaining, option)));
                default -> {
                    if (option.startsWith("-")) {
                        throw new CliException("Unknown option: " + option,
                                FaultoraCli.EXIT_INVALID_CONFIG);
                    }
                    paths.add(Path.of(option));
                }
            }
        }
        if (paths.isEmpty()) {
            throw new CliException(
                    "Nothing to migrate: name a file or a directory",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }

        List<Path> documents = new ArrayList<>();
        for (Path path : paths) {
            documents.addAll(documentsUnder(path));
        }

        int migrated = 0;
        for (Path document : documents) {
            if (migrate(document, write)) {
                migrated++;
            }
        }

        if (migrated == 0) {
            out.println("Nothing to migrate: " + documents.size()
                    + " document(s) already declare " + ApiVersions.CURRENT);
        } else if (write) {
            out.println("Migrated " + migrated + " document(s) to " + ApiVersions.CURRENT);
        } else {
            out.println(migrated + " document(s) would move to " + ApiVersions.CURRENT
                    + ". Re-run with --write to change them.");
        }
        return FaultoraCli.EXIT_PASS;
    }

    /** YAML under a path, or the path itself when it names a file. */
    private static List<Path> documentsUnder(Path path) {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        if (!Files.isDirectory(path)) {
            throw new CliException("No such file or directory: " + path,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
        try (var found = Files.walk(path)) {
            return found.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new CliException("Could not read " + path + ": " + unreadable.getMessage(),
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    /**
     * Move one document, or say why it is being left alone.
     *
     * @return whether this document needed migrating
     */
    private boolean migrate(Path document, boolean write) {
        String before;
        try {
            before = Files.readString(document);
        } catch (IOException unreadable) {
            err.println("Skipped " + document + ": " + unreadable.getMessage());
            return false;
        }

        Matcher version = API_VERSION_LINE.matcher(before);
        if (!version.find()) {
            // Not a Faultora document. An OpenAPI or AsyncAPI file sitting in
            // the same directory is the ordinary case, not a problem.
            return false;
        }
        String declared = version.group(2);
        if (!ApiVersions.isDeprecated(declared)) {
            return false;
        }

        String after = new StringBuilder(before)
                .replace(version.start(2), version.end(2), ApiVersions.CURRENT)
                .toString();

        if (!write) {
            out.println(document + ": " + declared + " → " + ApiVersions.CURRENT);
            return true;
        }
        try {
            writeAtomically(document, after);
        } catch (IOException unwritable) {
            err.println("Could not write " + document + ": " + unwritable.getMessage());
            return false;
        }
        out.println(document + ": " + declared + " → " + ApiVersions.CURRENT);
        return true;
    }

    /**
     * Replace a document's contents in one step.
     * <p>
     * Nothing here judges whether the document was any good: this tool moves a
     * version, and a scenario that was already invalid stays exactly as invalid
     * afterwards. Refusing to touch it would be this command answering a
     * question {@code faultora validate} asks better.
     * <p>
     * An observation catalog is moved too. Its {@code apiVersion} is not
     * validated by anything today, so that is tidiness rather than
     * compatibility, and ADR-022 says so rather than letting the version line
     * there look like a rule it is not.
     */
    private static void writeAtomically(Path document, String migrated) throws IOException {
        Path partial = document.resolveSibling(document.getFileName() + ".migrating");
        Files.writeString(partial, migrated);
        try {
            Files.move(partial, document,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException cannotSwap) {
            Files.deleteIfExists(partial);
            throw cannotSwap;
        }
    }

    private static String value(Iterator<String> remaining, String option) {
        if (!remaining.hasNext()) {
            throw new CliException(option + " needs a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return remaining.next();
    }

    private void printUsage() {
        out.println("Usage: faultora migrate <file or directory>... [--write]");
        out.println();
        out.println("Moves documents to " + ApiVersions.CURRENT + ".");
        out.println("Only the apiVersion line changes: " + ApiVersions.CURRENT
                + " froze the semantics");
        out.println(String.join(" and ", ApiVersions.DEPRECATED) + " already had.");
        out.println();
        out.println("  --write     Change the files. Without it, this only reports.");
        out.println();
        out.println("Comments, key order and formatting are preserved — the edit is");
        out.println("one token, not a round trip through a YAML writer.");
    }
}
