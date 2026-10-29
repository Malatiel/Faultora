package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much memory, time and output a run takes as it grows, measured rather
 * than assumed.
 * <p>
 * The packaged CLI runs in its own JVM, with a fixed heap ceiling and GC
 * logging, against a local server that answers every request with a body of a
 * fixed size. The grid crosses the number of requests with the size of each
 * response, because evidence is held per node for the assertions that may
 * read it, and that product is what a run holds. The result is a table in
 * {@code integration-tests/target/capacity/baseline.md}; docs/CAPACITY.md
 * records one such run and what it implies.
 * <p>
 * Not part of the ordinary build: the name keeps it out of test discovery, so a
 * qualification run neither runs nor skips it. Run it by naming it:
 * {@code -Dtest=CapacityBaseline}.
 */
class CapacityBaseline {
    private static final int[] REQUESTS = {100, 500, 1000};
    private static final int[] BODY_BYTES = {1024, 65_536, 262_144};
    private static final String HEAP_CEILING = "-Xmx1g";
    private static final Pattern PAUSE = Pattern.compile("Pause.*?(\\d+)M->(\\d+)M\\((\\d+)M\\)");
    private static final Pattern EXIT_USED = Pattern.compile("used (\\d+)K");

    @Test
    void measureTheGrid() throws Exception {
        // Under target rather than the system temporary directory, whose path
        // the JVM's own log option has to be able to name.
        Path out = Path.of(System.getProperty("faultora.projectRoot"), "integration-tests", "target", "capacity");
        Path runs = out.resolve("runs");
        Files.createDirectories(runs);
        List<String> rows = new ArrayList<>();
        rows.add("| Requests | Body | Wall time | Max heap before GC | Max heap after GC | Heap used at exit "
                + "| GCs | Output on disk |");
        rows.add("|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (int bodyBytes : BODY_BYTES) {
            HttpServer server = server(bodyBytes);
            try {
                String target = "http://127.0.0.1:" + server.getAddress().getPort();
                for (int requests : REQUESTS) {
                    rows.add(measure(runs, requests, bodyBytes, target));
                }
            } finally {
                server.stop(0);
            }
        }
        String table = String.join(System.lineSeparator(), rows) + System.lineSeparator();
        Files.writeString(out.resolve("baseline.md"), "Heap ceiling " + HEAP_CEILING + ", Java "
                + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " processors"
                + System.lineSeparator() + System.lineSeparator() + table);
        System.out.println(table);
    }

    private static String measure(Path runs, int requests, int bodyBytes, String target) throws Exception {
        Path run = runs.resolve("run-" + requests + "-" + bodyBytes);
        deleteRecursively(run);
        Files.createDirectories(run);
        Path gc = run.resolve("gc.log");
        Path output = run.resolve("output");
        Path scenario = scenario(run, requests);
        Path catalog = catalog(run);
        Path jar = Path.of(System.getProperty("faultora.cliJar")).toAbsolutePath();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), HEAP_CEILING,
                // Relative, from the run's directory: the option splits on colons.
                "-Xlog:gc,gc+heap+exit:file=gc.log"));
        String toolOptions = System.getenv("JAVA_TOOL_OPTIONS");
        if (toolOptions != null && !toolOptions.isBlank()) {
            command.addAll(List.of(toolOptions.trim().split("\\s+")));
        }
        command.addAll(List.of("-jar", jar.toString(), "test", "--scenario", scenario.toString(),
                "--openapi", catalog.toString(), "--target", target, "--allow-private",
                "--format", "json", "--output", output.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(run.toFile()).redirectErrorStream(true)
                .redirectOutput(run.resolve("cli.log").toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        long started = System.nanoTime();
        int exit = BoundedProcess.run(builder, Duration.ofMinutes(10), process -> { });
        long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(exit).as(Files.readString(run.resolve("cli.log"))).isZero();

        long before = 0;
        long after = 0;
        long collections = 0;
        long exitUsedKb = 0;
        for (String line : Files.readAllLines(gc)) {
            Matcher pause = PAUSE.matcher(line);
            if (pause.find()) {
                collections++;
                before = Math.max(before, Long.parseLong(pause.group(1)));
                after = Math.max(after, Long.parseLong(pause.group(2)));
            }
            Matcher used = EXIT_USED.matcher(line);
            if (line.contains("garbage-first heap") && used.find()) {
                exitUsedKb = Long.parseLong(used.group(1));
            }
        }
        long outputBytes;
        try (Stream<Path> files = Files.walk(output)) {
            outputBytes = files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
        return "| " + requests + " | " + kib(bodyBytes) + " | " + wallMs + " ms | " + before + " MiB | "
                + after + " MiB | " + exitUsedKb / 1024 + " MiB | " + collections + " | "
                + outputBytes / 1024 + " KiB |";
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static String kib(int bytes) {
        return bytes / 1024 + " KiB";
    }

    /** Groups of a hundred, the most one repeat step may hold. */
    private static Path scenario(Path run, int requests) throws Exception {
        StringBuilder yaml = new StringBuilder("""
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata:
                  name: capacity-baseline
                execute:
                """);
        for (int group = 0; group < requests / 100; group++) {
            yaml.append("  - id: batch-").append(group).append('\n')
                    .append("    type: repeat\n    count: 100\n    steps:\n")
                    .append("      - id: read-").append(group).append('\n')
                    .append("        type: operation\n        operationId: read-item\n");
        }
        yaml.append("""
                assertions:
                  - id: last-read-succeeded
                    assertionType: status
                    targetStep: read-0
                    params:
                      expected: 200
                """);
        return Files.writeString(run.resolve("scenario.yaml"), yaml.toString());
    }

    private static Path catalog(Path run) throws Exception {
        return Files.writeString(run.resolve("openapi.yaml"), """
                openapi: 3.0.3
                info:
                  title: Capacity baseline
                  version: '1'
                paths:
                  /item:
                    get:
                      operationId: read-item
                      responses:
                        '200':
                          description: A body of a fixed size
                          content:
                            application/json:
                              schema:
                                type: object
                """);
    }

    private static HttpServer server(int bodyBytes) throws Exception {
        byte[] body = ("{\"padding\":\"" + "x".repeat(bodyBytes - 14) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/item", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
