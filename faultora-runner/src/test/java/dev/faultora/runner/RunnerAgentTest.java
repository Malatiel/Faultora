package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.RunId;
import dev.faultora.runner.protocol.Progress;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.testkit.Certificates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the far side is told a run came to.
 * <p>
 * A dispatcher reads this and nothing else about a run that never produced a
 * result, so a shape that says something went wrong without saying what leaves
 * an operator with a runner inside a private network and no way to ask.
 */
class RunnerAgentTest {

    @TempDir
    Path directory;

    @Test
    void aRunThatBrokeSaysWhat() {
        // It said {"broke":true} and no more — the one outcome with no other
        // diagnosis anywhere was the one that carried none. A refusal names its
        // reason and a finished run has a journal behind it; this has neither.
        DispatchedRun.Outcome broke = DispatchedRun.Outcome.broke(
                Path.of("run.ndjson"),
                "run 'run-1' stopped part-way: the ledger refused the connection");

        assertThat(RunnerAgent.describe(broke))
                .contains("\"broke\":true")
                .contains("the ledger refused the connection");
    }

    @Test
    void aRefusalTravelsAsItsReasonAndItsWords() {
        DispatchedRun.Outcome refused = DispatchedRun.Outcome.refused(
                Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                        "the scenario is not the one that was sent"));

        assertThat(RunnerAgent.describe(refused))
                .contains("DIGEST_MISMATCH")
                .contains("the scenario is not the one that was sent");
    }

    @Test
    void aRunThatEndedSaysHowAndWhetherItsPermissionRanOut() {
        DispatchedRun.Outcome ran = DispatchedRun.Outcome.ran(passed(), Path.of("run.ndjson"), true, List.of());

        assertThat(RunnerAgent.describe(ran))
                .contains("\"status\":\"PASSED\"", "\"leaseExpired\":true")
                .doesNotContain("catalogWarnings");
    }

    @Test
    void aRunBuiltOnACatalogAnImporterWarnedAboutCarriesTheWarning() {
        // The importer skipped a server and the run went ahead without it. The
        // operator who sent the dispatch is the one who can fix the document,
        // and the runner's own console is not where they are looking.
        DispatchedRun.Outcome ran = DispatchedRun.Outcome.ran(passed(), Path.of("run.ndjson"), false,
                List.of(new CatalogAssembly.Warning("asyncapi", "Server 'legacy' names no host and was skipped")));

        assertThat(RunnerAgent.describe(ran)).contains(
                "\"catalogWarnings\":[{\"family\":\"asyncapi\","
                        + "\"message\":\"Server 'legacy' names no host and was skipped\"}]");
    }

    @Test
    void v1RetriesOnlyTheUnacknowledgedSuffixAndReportsPassAfterCompleteDelivery() throws Exception {
        var identity = Certificates.issue(directory, "dispatcher", 1);
        var tls = new TlsMaterial(identity.keystore(), Certificates.trusting(directory, "runner", identity),
                () -> Certificates.PASSWORD.toCharArray());
        var json = new ObjectMapper();
        List<Progress> progress = new CopyOnWriteArrayList<>();
        List<String> outcomes = new CopyOnWriteArrayList<>();
        var dispatcher = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dispatcher.setHttpsConfigurator(new HttpsConfigurator(tls.sslContext()));
        dispatcher.createContext("/runner/progress", exchange -> {
            try (exchange) {
                Progress message = json.readValue(exchange.getRequestBody(), Progress.class);
                progress.add(message);
                long acknowledged = message.fromPosition() + (message.runId().equals("run-stalled") ? 0 : 1);
                byte[] reply = Long.toString(acknowledged).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        dispatcher.createContext("/runner/result", exchange -> {
            try (exchange) {
                outcomes.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        dispatcher.start();
        try {
            var client = new RunnerClient(URI.create("https://127.0.0.1:" + dispatcher.getAddress().getPort()),
                    tls, "runner-1", "0.11.3", Set.of());
            Path journal = directory.resolve("events.ndjson");
            var passed = DispatchedRun.Outcome.ran(passed(), journal, false, List.of());
            String first = "{\"message\":\"перевод — 元帳\"}";
            String second = "{\"message\":\"done\"}";
            Files.writeString(journal, first + "\n" + second + "\n");

            RunnerAgent.deliverWhatIsLeft(client, "run-valid", passed, new JournalTail(journal), true);

            assertThat(progress).containsExactly(new Progress("run-valid", 0, List.of(first, second)),
                    new Progress("run-valid", 1, List.of(second)));
            assertThat(outcomes).singleElement().satisfies(outcome ->
                    assertThat(outcome).contains("\"status\":\"PASSED\""));

            RunnerAgent.deliverWhatIsLeft(client, "run-stalled", passed, new JournalTail(journal), true);

            assertThat(progress.getLast()).isEqualTo(new Progress("run-stalled", 0, List.of(first, second)));
            assertThat(progress).hasSize(3);
            assertIncompleteDelivery(outcomes.getLast());

            Files.writeString(journal, first + "\n{malformed}\n" + second + "\n");
            RunnerAgent.deliverWhatIsLeft(client, "run-corrupt", passed, new JournalTail(journal), true);

            assertThat(progress.getLast()).isEqualTo(new Progress("run-corrupt", 0, List.of(first)));
            assertIncompleteDelivery(outcomes.getLast());

            Files.writeString(journal, first + "\n" + second);
            RunnerAgent.deliverWhatIsLeft(client, "run-unfinished", passed, new JournalTail(journal), true);

            assertThat(progress.getLast()).isEqualTo(new Progress("run-unfinished", 0, List.of(first)));
            assertIncompleteDelivery(outcomes.getLast());
            assertThat(outcomes).hasSize(4);
        } finally {
            dispatcher.stop(0);
        }
    }

    private static void assertIncompleteDelivery(String outcome) {
        assertThat(outcome).contains("\"broke\":true", "journal delivery incomplete")
                .doesNotContain("PASSED");
    }

    private static RunResult passed() {
        return new RunResult(new RunId("run-1"), RunResult.Status.PASSED, 3, 2, 0, Map.of(), 10, null);
    }
}
