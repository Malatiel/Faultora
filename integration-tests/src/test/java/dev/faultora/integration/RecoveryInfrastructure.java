package dev.faultora.integration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

/**
 * The broker and the database the reference system runs against.
 * <p>
 * Started once for the JVM and shared by every suite that needs them, because
 * two suites proving the same invariant — one through the CLI, one through a
 * runner — should differ in how the run reaches the system and in nothing else.
 * Torn down by the container runtime's own reaper at exit rather than by a
 * {@code @AfterAll}, which is what lets the second suite still have them.
 * <p>
 * The applications are deliberately <em>not</em> here. Containers are shared;
 * processes and tables are not, so a variant of the reference system cannot
 * inherit rows or a consumer from the test before it.
 */
final class RecoveryInfrastructure {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka:3.8.0");
    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine");

    /** The password of the read-only role, as the surefire environment holds it. */
    static final String READ_ONLY_PASSWORD = "faultora-readonly";

    /** The user the observations connect as, which holds only {@code GRANT SELECT}. */
    static final String READ_ONLY_USER = "faultora_readonly";

    /** Handle the scenario's database password is resolved under. */
    static final String SECRET_ID = "ledger-password";

    private static KafkaContainer kafka;
    private static PostgreSQLContainer<?> postgres;

    private RecoveryInfrastructure() {
    }

    /** Whether a container runtime exists, so a suite can skip itself rather than fail. */
    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException noRuntime) {
            return false;
        }
    }

    /** Start the infrastructure, or return the already-running one. */
    static synchronized void start() throws Exception {
        if (kafka != null) {
            return;
        }
        KafkaContainer broker = new KafkaContainer(KAFKA_IMAGE);
        broker.start();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBootstrapServers().replace("PLAINTEXT://", "")))) {
            // Declared rather than auto-created: auto-creation races the first
            // observation, and the failure looks like the defect under test.
            admin.createTopics(List.of(
                    new NewTopic("payment-commands", 1, (short) 1),
                    new NewTopic("payment-events", 1, (short) 1))).all().get();
        }
        PostgreSQLContainer<?> database = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("payments")
                .withUsername("payments")
                .withPassword("payments");
        database.start();

        kafka = broker;
        postgres = database;
    }

    static String bootstrapServers() {
        return kafka.getBootstrapServers().replace("PLAINTEXT://", "");
    }

    static String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    static String databaseOwner() {
        return postgres.getUsername();
    }

    static String databaseOwnerPassword() {
        return postgres.getPassword();
    }
}
