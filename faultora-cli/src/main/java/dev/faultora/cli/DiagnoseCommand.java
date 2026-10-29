package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code faultora diagnose} — a file to attach to a bug report that holds what
 * the run did and nothing it saw.
 * <p>
 * The bundle is built from an allowlist, never by collecting a directory and
 * filtering: only the run's journal is read, each event is rewritten with only
 * the fields listed here, and of an error only its category and code survive.
 * Free text — assertion messages, skip reasons, error messages — is withheld
 * however the policy that wrote it treated it, and so are the run's resolved
 * configuration, target scopes and channel names, which name things inside
 * the network the run was in. Anything else in the run directory, a key or an
 * environment file left beside it included, is never opened.
 * <p>
 * A journal that is a link, or resolves outside the run directory, is refused
 * rather than followed, and so is one larger than the bundle allows.
 */
final class DiagnoseCommand implements Command {

    /** The most journal this reads; a larger one is refused, not truncated. */
    static final long MAX_JOURNAL_BYTES = 16L * 1024 * 1024;

    /** Fields an event keeps: identifiers, counts, durations, outcomes and digests. */
    static final Set<String> EVENT_FIELDS = Set.of(
            "eventType", "timestamp", "runId", "nodeId", "nodeType", "operationId",
            "assertionType", "outcome", "statusCode", "durationMs", "elapsedMs", "responseBytes",
            "attempt", "attemptNumber", "maxAttempts", "failedAttempt", "nextDelayMs", "errorCode",
            "maxPolls", "satisfied", "evidenceType", "sizeBytes", "digest", "scenarioDigest",
            "catalogDigest", "seed", "strategy", "faultType", "faultHandle", "hardExpiryMs",
            "rollbackStatus", "pendingObligations", "succeeded", "failed", "passedAssertions",
            "failedAssertions", "totalNodes", "fetched", "rows", "truncated", "observed", "matched",
            "requestedWaitMs", "waitedMs", "partition", "offset");

    /** Of a normalized error, what says which kind of failure it was. */
    static final Set<String> ERROR_FIELDS = Set.of("category", "code", "retryable");

    /** Of a runner status file, what says how the runner is. */
    static final Set<String> HEALTH_FIELDS = Set.of(
            "state", "runnerId", "registered", "currentRunId", "runsServed", "updatedAtEpochMs");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FileTime EPOCH = FileTime.from(Instant.EPOCH);

    private final PrintWriter out;
    private final PrintWriter err;

    DiagnoseCommand(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public int execute(List<String> args) {
        Path run = null;
        Path output = null;
        Path health = null;
        Iterator<String> remaining = args.iterator();
        while (remaining.hasNext()) {
            String option = remaining.next();
            switch (option) {
                case "--help", "-h" -> {
                    printUsage();
                    return FaultoraCli.EXIT_PASS;
                }
                case "--run" -> run = Path.of(value(remaining, option));
                case "--output" -> output = Path.of(value(remaining, option));
                case "--health" -> health = Path.of(value(remaining, option));
                default -> throw new CliException("Unknown option: " + option, FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }
        if (run == null || output == null) {
            throw new CliException("diagnose needs --run <run directory> and --output <bundle.zip>",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliException("Not overwriting " + output + "; name a new file",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }

        Path journal = contained(run, "events.ndjson");
        Path status = health == null ? null : checkedFile(health, health.getFileName().toString());
        Set<String> withheld = new TreeSet<>();
        ArrayNode files = JSON.createArrayNode();
        byte[] events = projectJournal(journal, withheld, files);
        byte[] healthBytes = status == null ? null : projectHealth(status, withheld, files);

        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("bundleFormat", 1);
        manifest.put("faultoraVersion", dev.faultora.runtime.FaultoraVersion.current());
        manifest.put("javaVersion", Runtime.version().toString());
        manifest.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        manifest.set("sources", files);
        ArrayNode dropped = manifest.putArray("fieldsWithheld");
        withheld.forEach(dropped::add);

        try (OutputStream file = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "manifest.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            entry(zip, "events.ndjson", events);
            if (healthBytes != null) {
                entry(zip, "health.json", healthBytes);
            }
        } catch (IOException failure) {
            err.println("Could not write " + output + ": " + failure.getClass().getSimpleName());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
        out.println("Wrote " + output + ": " + files.get(0).path("records").asInt() + " events"
                + (healthBytes == null ? "" : " and the runner status") + "; withheld "
                + (withheld.isEmpty() ? "nothing" : String.join(", ", withheld)));
        return FaultoraCli.EXIT_PASS;
    }

    /** The named file directly inside {@code directory}: a regular file, not a link, not elsewhere. */
    private static Path contained(Path directory, String name) {
        Path base;
        try {
            base = directory.toRealPath();
        } catch (IOException missing) {
            throw new CliException("No run directory at " + directory, FaultoraCli.EXIT_INVALID_CONFIG);
        }
        Path file = checkedFile(base.resolve(name), name);
        try {
            if (!file.toRealPath().getParent().equals(base)) {
                throw new CliException(name + " resolves outside " + directory, FaultoraCli.EXIT_INVALID_CONFIG);
            }
        } catch (IOException unreadable) {
            throw new CliException(name + " cannot be read", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return file;
    }

    private static Path checkedFile(Path file, String name) {
        if (Files.isSymbolicLink(file)) {
            throw new CliException(name + " is a link; diagnose reads no link", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliException("No " + name + " at " + file, FaultoraCli.EXIT_INVALID_CONFIG);
        }
        try {
            if (Files.size(file) > MAX_JOURNAL_BYTES) {
                throw new CliException(name + " is larger than the " + MAX_JOURNAL_BYTES
                        + " bytes a bundle takes", FaultoraCli.EXIT_INVALID_CONFIG);
            }
        } catch (IOException unreadable) {
            throw new CliException(name + " cannot be read", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return file;
    }

    private static byte[] projectJournal(Path journal, Set<String> withheld, ArrayNode files) {
        StringBuilder projected = new StringBuilder();
        int records = 0;
        try (BufferedReader lines = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = JSON.readTree(line);
                if (event == null || !event.isObject() || !event.path("eventType").isTextual()) {
                    throw new CliException("events.ndjson line " + (records + 1) + " is not a journal event",
                            FaultoraCli.EXIT_INVALID_CONFIG);
                }
                projected.append(JSON.writeValueAsString(project(event, EVENT_FIELDS, withheld))).append('\n');
                records++;
            }
        } catch (CliException refused) {
            throw refused;
        } catch (IOException unreadable) {
            throw new CliException("events.ndjson is not a readable journal", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        files.add(source("events.ndjson", journal, records));
        return projected.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] projectHealth(Path status, Set<String> withheld, ArrayNode files) {
        try {
            JsonNode tree = JSON.readTree(Files.readAllBytes(status));
            if (tree == null || !tree.isObject()) {
                throw new CliException("The status file is not a runner status", FaultoraCli.EXIT_INVALID_CONFIG);
            }
            files.add(source("health.json", status, 1));
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(project(tree, HEALTH_FIELDS, withheld));
        } catch (CliException refused) {
            throw refused;
        } catch (IOException unreadable) {
            throw new CliException("The status file is not a runner status", FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static ObjectNode project(JsonNode source, Set<String> allowed, Set<String> withheld) {
        ObjectNode kept = JSON.createObjectNode();
        source.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (name.equals("error") && value.isObject()) {
                kept.set(name, project(value, ERROR_FIELDS, withheld));
            } else if (name.equals("error") && value.isNull()) {
                kept.set(name, value);
            } else if (allowed.contains(name) && (value.isValueNode() || value.isNull())) {
                kept.set(name, value);
            } else {
                withheld.add(name);
            }
        });
        return kept;
    }

    private static ObjectNode source(String name, Path file, int records) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("path", name);
        entry.put("records", records);
        try {
            entry.put("bytes", Files.size(file));
            entry.put("sha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            throw new CliException(name + " cannot be read", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return entry;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(EPOCH);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static String value(Iterator<String> remaining, String option) {
        if (!remaining.hasNext()) {
            throw new CliException(option + " needs a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return remaining.next();
    }

    private void printUsage() {
        out.println("Usage: faultora diagnose --run <run directory> --output <bundle.zip> [--health <file>]");
        out.println();
        out.println("Writes a zip to attach to a bug report: the run's journal, each event reduced to");
        out.println("identifiers, counts, durations, outcomes and digests, and a manifest of what was");
        out.println("read and which fields were withheld. Nothing else in the run directory is read.");
        out.println();
        out.println("  --run <dir>       The directory `faultora test --output` wrote");
        out.println("  --output <file>   The bundle to write; an existing file is not overwritten");
        out.println("  --health <file>   Also include a runner status file, reduced the same way");
    }
}
