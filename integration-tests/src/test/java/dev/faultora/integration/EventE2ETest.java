package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.events.PaymentWorker;
import dev.faultora.model.events.RunEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release gate for events, run through the packaged CLI against a real
 * broker and a real consumer.
 * <p>
 * Everything below the wire is already covered by unit tests with in-memory
 * clients. What only a real broker can show is whether the assumptions those
 * tests encode are true: that an offset floor taken before a publish is below
 * what the publish writes, that a consumer assigned to partitions sees records
 * a separate application produced, and that the whole thing survives being
 * driven by the CLI rather than by a test harness.
 * <p>
 * The suite skips itself when no container runtime is available, so an offline
 * build stays green — and says so out loud, because a skipped gate must never
 * read as a passed one.
 */
@EnabledIf("dockerIsAvailable")
class EventE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The broker image the disposable Kafka runs. */
    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka:3.8.0");

    private KafkaContainer kafka;
    private PaymentWorker worker;
    @TempDir Path replayDirectory;

    @SuppressWarnings("unused")
    static boolean dockerIsAvailable() {
        return RecoveryInfrastructure.dockerIsAvailable();
    }

    private void startBroker(boolean idempotentWorker) throws Exception {
        kafka = new KafkaContainer(KAFKA_IMAGE);
        kafka.start();
        createTopics();
        worker = new PaymentWorker(kafka.getBootstrapServers(), idempotentWorker);
        worker.start();
    }

    /**
     * Both topics exist before the run.
     * <p>
     * A deployment declares its topics; leaving them to be auto-created would
     * make the first observation race the first publish, and the failure would
     * look like the defect the scenario is meant to find.
     */
    private void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(PaymentWorker.COMMANDS_TOPIC, 1, (short) 1),
                    new NewTopic(PaymentWorker.EVENTS_TOPIC, 1, (short) 1))).all().get();
        }
    }

    @BeforeEach
    void noLeftovers() {
        kafka = null;
        worker = null;
    }

    @AfterEach
    void stopEverything() {
        if (worker != null) worker.close();
        if (kafka != null) kafka.stop();
    }

    private int run(Path outputDir, String seed) throws IOException {
        // Emptied rather than reused. A journal left by an earlier run of this
        // suite would let an assertion about this run's events be satisfied by
        // the previous one's — an assertion that cannot fail.
        if (Files.isDirectory(outputDir)) {
            try (var entries = Files.list(outputDir)) {
                for (Path leftover : entries.toList()) {
                    Files.deleteIfExists(leftover);
                }
            }
        }
        Files.createDirectories(outputDir);
        // The report is kept as well as printed. When this suite fails on a
        // machine nobody can attach to, the exit code alone starts a guessing
        // game — which is how the last CI failure was diagnosed twice and
        // wrongly once. The assertion carries what the run said.
        report = new StringWriter();
        PrintWriter out = new PrintWriter(new TeeWriter(
                new PrintWriter(System.out, true), report), true);
        return new FaultoraCli(out, out)
                .run(new String[]{
                        "test",
                        "--scenario", ExampleFixtures.workerScenario(
                                "duplicate-delivery.yaml").toString(),
                        "--asyncapi", ExampleFixtures.asyncApi().toString(),
                        "--target", "broker=kafka://" + kafka.getBootstrapServers()
                                .replace("PLAINTEXT://", ""),
                        "--allow-private",
                        "--seed", seed,
                        "--format", "console,json",
                        "--output", outputDir.toString()
                });
    }

    /** What the last run printed, for a failure message that explains itself. */
    private StringWriter report;

    /**
     * The run's own report, and what the worker did, in the failure message.
     * <p>
     * The worker's count is the half the CLI cannot see, and it splits the
     * remaining question in two. A settlement count of zero means the command
     * never became an effect — the worker did not consume it, or could not
     * publish what it settled. A count of one with nothing observed means the
     * effect happened and the observation did not see it, which is a defect in
     * the window rather than in the pipeline. Those are different fixes, and
     * the exit code alone cannot tell them apart.
     */
    private String whatTheRunSaid() {
        return (report == null ? "(the run printed nothing)" : "\n" + report)
                + "\nthe worker settled "
                + (worker == null ? "(no worker)" : worker.settledCount())
                + " payment(s) — a count of zero means no settlement event was ever"
                + " produced, so nothing was there to observe";
    }

    /** Writes to both, so the console keeps its output and the test keeps a copy. */
    private static final class TeeWriter extends Writer {

        private final Writer console;
        private final Writer kept;

        private TeeWriter(Writer console, Writer kept) {
            this.console = console;
            this.kept = kept;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            console.write(buffer, offset, length);
            kept.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            kept.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    @Test
    void aCommandDeliveredTwiceSettlesThePaymentOnce() throws Exception {
        startBroker(true);
        Path outputDir = Files.createTempDirectory("faultora-e2e-events");

        int exit = run(outputDir, "77001");

        assertThat(exit).as(this::whatTheRunSaid).isEqualTo(FaultoraCli.EXIT_PASS);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        // Two commands were published, and the journal says where each landed.
        assertThat(events.split("MESSAGE_PUBLISHED", -1)).hasSizeGreaterThan(2);
        assertThat(events).contains("MESSAGES_OBSERVED");
        assertThat(worker.settledCount())
                .as("the worker settled one payment, not two")
                .isEqualTo(1);
    }

    @Test
    void theSameScenarioFailsAgainstAWorkerThatIsNotIdempotent() throws Exception {
        // A reliability test that has never failed proves nothing. This is the
        // same scenario against the deliberately broken worker: it must catch
        // the duplicate rather than pass by construction.
        startBroker(false);
        Path outputDir = Files.createTempDirectory("faultora-e2e-events-broken");

        int exit = run(outputDir, "77002");

        assertThat(exit).as(this::whatTheRunSaid).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("one-effect-per-command");
        List<RunEvent> journal = readEvents(outputDir);
        assertThat(journal.stream().filter(RunEvent.MessagesObserved.class::isInstance)
                .map(RunEvent.MessagesObserved.class::cast)
                .filter(observed -> observed.nodeId().value().equals("read-settlements")))
                .singleElement().satisfies(observed -> assertThat(observed.matched()).isEqualTo(2));
        assertThat(journal.stream().filter(RunEvent.AssertionEvaluated.class::isInstance)
                .map(RunEvent.AssertionEvaluated.class::cast)
                .filter(assertion -> assertion.outcome().equals("FAIL"))
                .map(assertion -> assertion.nodeId().value()))
                .containsExactly("one-effect-per-command", "no-payment-settled-twice");
    }

    @Test
    void theSameSeedReplaysTheSameExchange() throws Exception {
        startBroker(true);
        Path first = replayDirectory.resolve("first");

        assertThat(run(first, "77003")).as(this::whatTheRunSaid)
                .isEqualTo(FaultoraCli.EXIT_PASS);

        List<String> firstDigests = publishedDigests(first);
        assertThat(firstDigests).hasSize(2).allMatch(digest -> digest.matches("sha256:[a-f0-9]{64}"));
        assertThat(firstDigests.get(0)).isEqualTo(firstDigests.get(1));
        assertThat(worker.settledCount()).isEqualTo(1);

        // Replay with fresh broker and worker state; idempotence from the first
        // exchange must not prevent the second exchange from producing evidence.
        stopEverything();
        noLeftovers();
        startBroker(true);
        Path second = replayDirectory.resolve("second");
        assertThat(run(second, "77003")).as(this::whatTheRunSaid).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(publishedDigests(second)).containsExactlyElementsOf(firstDigests);
        assertThat(worker.settledCount()).isEqualTo(1);
        for (Path output : List.of(first, second)) {
            assertThat(Files.readString(output.resolve("events.ndjson"))).doesNotContain("pay-77003");
            assertThat(readEvents(output).stream().filter(RunEvent.MessagePublished.class::isInstance)
                    .map(RunEvent.MessagePublished.class::cast)).allSatisfy(published -> assertThat(published.key()).isNull());
        }
    }

    private static List<String> publishedDigests(Path outputDir) throws IOException {
        return readEvents(outputDir).stream().filter(RunEvent.MessagePublished.class::isInstance)
                .map(RunEvent.MessagePublished.class::cast).map(RunEvent.MessagePublished::digest).toList();
    }

    private static List<RunEvent> readEvents(Path outputDir) throws IOException {
        List<RunEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(outputDir.resolve("events.ndjson"))) {
            if (!line.isBlank()) {
                events.add(MAPPER.readValue(line, RunEvent.class));
            }
        }
        return List.copyOf(events);
    }
}
