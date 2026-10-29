package dev.faultora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
 * rather than followed, and so is one larger than the bundle allows. A kept
 * field whose value is not the shape that field has — text where a number
 * belongs — refuses the bundle instead of carrying that text out under a safe
 * name, and a field the journal is not known to carry is counted, not named.
 */
final class DiagnoseCommand implements Command {

    /** The most journal this reads; a larger one is refused, not truncated. */
    static final long MAX_JOURNAL_BYTES = 16L * 1024 * 1024;

    /**
     * Fields an event keeps — identifiers, counts, durations, outcomes and
     * digests — each with the only shape it may have. A kept field of another
     * shape refuses the bundle: a name is no promise about what it holds.
     */
    static final Map<String, Shape> EVENT_FIELDS = shapes(
            Shape.TOKEN, List.of("eventType", "runId", "nodeId", "nodeType", "operationId", "assertionType",
                    "outcome", "errorCode", "evidenceType", "digest", "scenarioDigest", "catalogDigest",
                    "strategy", "faultType", "faultHandle", "rollbackStatus"),
            Shape.NUMBER, List.of("timestamp", "statusCode", "durationMs", "elapsedMs", "responseBytes",
                    "attempt", "attemptNumber", "maxAttempts", "failedAttempt", "nextDelayMs", "maxPolls",
                    "sizeBytes", "seed", "hardExpiryMs", "pendingObligations", "succeeded", "failed",
                    "passedAssertions", "failedAssertions", "totalNodes", "fetched", "rows", "observed",
                    "matched", "requestedWaitMs", "waitedMs", "partition", "offset"),
            Shape.BOOLEAN, List.of("satisfied", "truncated"));

    /** Of a normalized error, what says which kind of failure it was. */
    static final Map<String, Shape> ERROR_FIELDS = shapes(
            Shape.TOKEN, List.of("category", "code"), Shape.NUMBER, List.of(), Shape.BOOLEAN, List.of("retryable"));

    /** Of a runner status file, what says how the runner is. */
    static final Map<String, Shape> HEALTH_FIELDS = shapes(
            Shape.TOKEN, List.of("state", "runnerId", "currentRunId"),
            Shape.NUMBER, List.of("runsServed", "updatedAtEpochMs"), Shape.BOOLEAN, List.of("registered"));

    /**
     * Fields the journal or a status file is known to carry and the bundle
     * withholds; the manifest names these. A field of any other name is
     * withheld and counted, never named, since its name may be private too.
     */
    static final Set<String> KNOWN_WITHHELD = Set.of(
            "message", "detail", "reason", "resolvedConfig", "targetScope", "channel", "key", "field",
            "schemaId", "violation", "metadata");

    /** The identifier shape of {@code TypedId}; anything with a space or a quote is not one. */
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9._:/@-]{1,256}");

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
        Withheld withheld = new Withheld();
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
        withheld.named.forEach(dropped::add);
        manifest.put("unnamedFieldsWithheld", withheld.unnamed);

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
                + (healthBytes == null ? "" : " and the runner status") + "; withheld " + withheld);
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
        return file;
    }

    /**
     * The file's bytes, read once and at most {@link #MAX_JOURNAL_BYTES} of
     * them: a journal still growing is refused once it passes the limit, and
     * what is projected and what the manifest hashes are the same bytes.
     */
    static byte[] snapshot(Path file, String name) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes((int) MAX_JOURNAL_BYTES + 1);
        } catch (IOException unreadable) {
            throw new CliException(name + " cannot be read", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        if (bytes.length > MAX_JOURNAL_BYTES) {
            throw new CliException(name + " is larger than the " + MAX_JOURNAL_BYTES
                    + " bytes a bundle takes", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return bytes;
    }

    private static byte[] projectJournal(Path journal, Withheld withheld, ArrayNode files) {
        byte[] bytes = snapshot(journal, "events.ndjson");
        StringBuilder projected = new StringBuilder();
        int records = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event;
            try {
                event = JSON.readTree(line);
            } catch (IOException unreadable) {
                throw new CliException("events.ndjson is not a readable journal", FaultoraCli.EXIT_INVALID_CONFIG);
            }
            String where = "events.ndjson line " + (records + 1);
            if (event == null || !event.isObject() || !event.path("eventType").isTextual()) {
                throw new CliException(where + " is not a journal event", FaultoraCli.EXIT_INVALID_CONFIG);
            }
            try {
                projected.append(JSON.writeValueAsString(project(event, EVENT_FIELDS, withheld, where))).append('\n');
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            records++;
        }
        files.add(source("events.ndjson", bytes, records));
        return projected.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] projectHealth(Path status, Withheld withheld, ArrayNode files) {
        byte[] bytes = snapshot(status, "The status file");
        JsonNode tree;
        try {
            tree = JSON.readTree(bytes);
        } catch (IOException unreadable) {
            tree = null;
        }
        if (tree == null || !tree.isObject()) {
            throw new CliException("The status file is not a runner status", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        ObjectNode kept = project(tree, HEALTH_FIELDS, withheld, "The status file");
        files.add(source("health.json", bytes, 1));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(kept);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ObjectNode project(JsonNode source, Map<String, Shape> allowed, Withheld withheld, String where) {
        ObjectNode kept = JSON.createObjectNode();
        source.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (allowed == EVENT_FIELDS && name.equals("error") && (value.isObject() || value.isNull())) {
                kept.set(name, value.isNull() ? value : project(value, ERROR_FIELDS, withheld, where + " error"));
            } else if (allowed.containsKey(name)) {
                if (!value.isNull() && !allowed.get(name).admits(value)) {
                    throw new CliException(where + ": " + name + " is not " + allowed.get(name).description,
                            FaultoraCli.EXIT_INVALID_CONFIG);
                }
                kept.set(name, value);
            } else if (KNOWN_WITHHELD.contains(name)) {
                withheld.named.add(name);
            } else {
                withheld.unnamed++;
            }
        });
        return kept;
    }

    private static ObjectNode source(String name, byte[] bytes, int records) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("path", name);
        entry.put("records", records);
        entry.put("bytes", bytes.length);
        try {
            entry.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return entry;
    }

    private static Map<String, Shape> shapes(Shape first, List<String> firstNames, Shape second,
            List<String> secondNames, Shape third, List<String> thirdNames) {
        Map<String, Shape> shapes = new HashMap<>();
        firstNames.forEach(name -> shapes.put(name, first));
        secondNames.forEach(name -> shapes.put(name, second));
        thirdNames.forEach(name -> shapes.put(name, third));
        return Map.copyOf(shapes);
    }

    /** What a kept field may hold. */
    enum Shape {
        TOKEN("an identifier"),
        NUMBER("a number"),
        BOOLEAN("true or false");

        private final String description;

        Shape(String description) {
            this.description = description;
        }

        boolean admits(JsonNode value) {
            return switch (this) {
                case TOKEN -> value.isTextual() && DiagnoseCommand.TOKEN.matcher(value.asText()).matches();
                case NUMBER -> value.isNumber();
                case BOOLEAN -> value.isBoolean();
            };
        }
    }

    /** Which fields were left out: known ones by name, any others only counted. */
    private static final class Withheld {
        private final Set<String> named = new TreeSet<>();
        private int unnamed;

        @Override
        public String toString() {
            if (named.isEmpty() && unnamed == 0) {
                return "nothing";
            }
            String names = String.join(", ", named);
            if (unnamed == 0) {
                return names;
            }
            String count = unnamed + " field" + (unnamed == 1 ? "" : "s") + " of no known name";
            return named.isEmpty() ? count : names + " and " + count;
        }
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
