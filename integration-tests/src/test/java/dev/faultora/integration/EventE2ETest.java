package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import dev.faultora.examples.payment.events.PaymentWorker;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    /** The broker image the disposable Kafka runs. */
    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka:3.8.0");

    private KafkaContainer kafka;
    private PaymentWorker worker;

    @SuppressWarnings("unused")
    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException noRuntime) {
            System.err.println("Event end-to-end suite skipped: no container runtime. "
                    + "The events release gate is NOT covered by this build.");
            return false;
        }
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
        return new FaultoraCli(new PrintWriter(System.out, true), new PrintWriter(System.err, true))
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

    @Test
    void aCommandDeliveredTwiceSettlesThePaymentOnce() throws Exception {
        startBroker(true);
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-events");

        int exit = run(outputDir, "77001");

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_PASS);
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
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-events-broken");

        int exit = run(outputDir, "77002");

        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        String events = Files.readString(outputDir.resolve("events.ndjson"));
        assertThat(events).contains("one-effect-per-command");
        assertThat(events).contains("observed 2");
    }

    @Test
    void theSameSeedReplaysTheSameExchange() throws Exception {
        startBroker(true);
        Path first = Path.of(System.getProperty("java.io.tmpdir"), "faultora-e2e-events-1");

        assertThat(run(first, "77003")).isEqualTo(FaultoraCli.EXIT_PASS);

        // The payment id is derived from the seed, so the run names the same
        // exchange every time — which is what makes a failure investigable.
        assertThat(Files.readString(first.resolve("events.ndjson")))
                .contains("pay-77003");
    }
}
