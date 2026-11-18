package dev.faultora.integration;

import com.sun.net.httpserver.HttpServer;
import dev.faultora.engine.journal.RunEventReader;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.runner.protocol.Dispatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Repeated durable dispatches on one registration. Run with {@code -Dtest=RunnerSoakBaseline}. */
class RunnerSoakBaseline {
    private static final int RUNS = 100;
    private static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata:
              name: runner-soak
            execute:
              - id: read
                operationId: read-item
            assertions:
              - id: response-status
                assertionType: status
                targetStep: read
                params: {expected: 200}
            """;
    private static final String OPEN_API = """
            openapi: 3.0.3
            info: {title: Synthetic, version: '1'}
            paths:
              /item:
                get:
                  operationId: read-item
                  responses:
                    '200': {description: An item}
            """;

    @TempDir Path directory;

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void repeatedlyServesReadOnlyDispatchesWithCompleteSeparateJournals() throws Exception {
        Path scenario = Files.writeString(directory.resolve("scenario.yaml"), SCENARIO);
        Path contract = Files.writeString(directory.resolve("openapi.yaml"), OPEN_API);
        var requests = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/item", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        target.start();

        try (var runner = new RemoteRunner(directory.resolve("runner"), Map.of(), true)) {
            var documents = Map.of("openapi", contract);
            var redirects = Map.of("", "http://127.0.0.1:" + target.getAddress().getPort());
            long[] timings = new long[RUNS];
            long journalBytes = 0;
            long spoolBytes = 0;
            long started = System.nanoTime();
            for (int index = 0; index < RUNS; index++) {
                String id = "soak-" + (index + 1);
                var request = new RemoteRunner.Request(id, scenario, documents, redirects,
                        Dispatch.Credentials.none(), 42L, 60_000L);
                long runStarted = System.nanoTime();
                var outcome = runner.run(request);
                timings[index] = System.nanoTime() - runStarted;
                assertThat(outcome.didRun()).as(() -> id + " executed: " + outcome.refusal()).isTrue();
                assertThat(outcome.result().runId().value()).as(id).isEqualTo(id);
                assertThat(outcome.result().status()).as(id).isEqualTo(RunResult.Status.PASSED);
                assertThat(outcome.result().passedAssertions()).as(id).isEqualTo(1);
                assertThat(outcome.leaseExpired()).as(id).isFalse();
                assertThat(requests).as(id + " target calls so far").hasValue(index + 1);

                Path journal = outcome.journalPath();
                var deliveredJournal = runner.durableJournalDelivered(id);
                assertThat(deliveredJournal).as(id + " delivered journal")
                        .containsExactlyElementsOf(Files.readAllLines(journal));
                assertThat(RunEventReader.readLine(deliveredJournal.getFirst())).as(id)
                        .hasValueSatisfying(event -> assertThat(event).isInstanceOf(RunEvent.RunStarted.class));
                assertThat(RunEventReader.readLine(deliveredJournal.getLast())).as(id)
                        .hasValueSatisfying(event -> assertThat(event).isInstanceOf(RunEvent.RunCompleted.class));
                var terminal = runner.durableTerminalDelivered(id);
                assertThat(terminal).as(id + " terminal").isNotNull();
                assertThat(terminal.attempt().runId()).as(id).isEqualTo(id);
                assertThat(terminal.status()).as(id).isEqualTo("PASSED");
                assertThat(terminal.finalPosition()).as(id).isEqualTo(deliveredJournal.size());
                assertThat(terminal.cleanupConfirmed()).as(id).isTrue();
                assertThat(terminal.leaseExpired()).as(id).isFalse();

                Path spool = journal.resolveSibling("spool.bin");
                journalBytes += Files.size(journal);
                spoolBytes += Files.size(spool);
                assertThat(runner.retireAcknowledged(id)).as(id + " acknowledged retirement")
                        .hasValueSatisfying(receipt -> assertThat(receipt.terminal()).isEqualTo(terminal));
                assertThat(journal).doesNotExist();
                assertThat(spool).doesNotExist();
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Arrays.sort(timings);
            int p95Index = (int) Math.ceil(timings.length * 0.95) - 1;
            System.out.printf(Locale.ROOT,
                    "Runner soak: %d runs, %d ms elapsed, %d ms p95 dispatch, %d journal bytes, %d spool bytes%n",
                    RUNS, elapsedMs, TimeUnit.NANOSECONDS.toMillis(timings[p95Index]), journalBytes, spoolBytes);
        } finally {
            target.stop(0);
        }
    }
}
