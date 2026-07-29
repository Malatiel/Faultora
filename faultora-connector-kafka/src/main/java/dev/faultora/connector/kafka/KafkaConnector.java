package dev.faultora.connector.kafka;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.result.OperationResult;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes commands and observes events on Kafka.
 * <p>
 * Two properties of this connector are decisions rather than defaults, and both
 * are about what a run leaves behind:
 * <ul>
 *   <li><b>It never joins a consumer group.</b> Partitions are assigned
 *       directly and no offset is ever committed, so a run creates no group
 *       state on the broker and cannot disturb the application's own consumers.
 *       An interrupted run leaves nothing to clean up, which matters for a tool
 *       whose subject is what happens when things are interrupted.</li>
 *   <li><b>It reads with the run's own identity.</b> The client id names the
 *       run, so a broker's logs and metrics attribute the traffic to the test
 *       rather than to an unexplained consumer.</li>
 * </ul>
 * Destination policy applies to every broker in the bootstrap list. What it
 * cannot do is pin the addresses it verified — see {@link BootstrapServers}.
 */
public final class KafkaConnector implements Connector {

    /** Protocol identifier of operations this connector executes. */
    public static final String PROTOCOL = "kafka";

    private static final ProtocolId PROTOCOL_ID = new ProtocolId(PROTOCOL);

    /** How far before the run's start an observation still looks, for clock skew. */
    private static final long CLOCK_GRACE_MS = 2_000;

    private final HostPolicy hostPolicy;
    private final KafkaClients clients;
    private final String runToken;
    private final long observeFromMs;
    private final Map<TargetDefinition, KafkaPreparedTarget> prepared = new ConcurrentHashMap<>();

    public KafkaConnector() {
        this(HostPolicy.defaultPolicy(), KafkaClients.real());
    }

    public KafkaConnector(HostPolicy hostPolicy) {
        this(hostPolicy, KafkaClients.real());
    }

    public KafkaConnector(HostPolicy hostPolicy, KafkaClients clients) {
        this.hostPolicy = hostPolicy;
        this.clients = clients;
        this.runToken = UUID.randomUUID().toString().substring(0, 8);
        // An observation looks back to when the run began, and no further. The
        // grace absorbs the difference between this machine's clock and the
        // one that stamped the record, which is a different machine whenever
        // the broker and the application are not this process.
        this.observeFromMs = System.currentTimeMillis() - CLOCK_GRACE_MS;
    }

    @Override
    public ProtocolId protocol() {
        return PROTOCOL_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("kafka-publish", "kafka-consume", "json-body", "message-headers");
    }

    @Override
    public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
        // Refused destinations throw, as they do for HTTP: a target the policy
        // rejects is a configuration error, not a failed operation.
        BootstrapServers bootstrap = BootstrapServers.parse(target.baseUrl(), hostPolicy);
        return prepared.computeIfAbsent(target, definition -> new KafkaPreparedTarget(
                definition, bootstrap, clients,
                producerConfig(bootstrap, context),
                consumerConfig(bootstrap, context),
                observeFromMs));
    }

    @Override
    public OperationResult execute(
            PreparedTarget preparedTarget,
            OperationDefinition operation,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        KafkaPreparedTarget target = (KafkaPreparedTarget) preparedTarget;
        Map<String, Object> resolved = inputs == null ? Map.of() : inputs;
        try {
            KafkaOperation kafkaOperation = KafkaOperation.of(operation);
            return switch (kafkaOperation.action()) {
                case PUBLISH ->
                        EventPublisher.publish(target, kafkaOperation, resolved, context);
                case CONSUME ->
                        EventObserver.observe(target, kafkaOperation, resolved, context);
            };
        } catch (IllegalArgumentException misconfigured) {
            return OperationResult.failure(
                    KafkaErrors.configuration(misconfigured.getMessage()), 0);
        } catch (IllegalStateException unavailable) {
            return OperationResult.failure(
                    KafkaErrors.unreachable(unavailable.getMessage()), 0);
        } catch (org.apache.kafka.common.KafkaException failed) {
            return OperationResult.failure(
                    KafkaErrors.of(failed, "talking to Kafka"), 0);
        }
    }

    @Override
    public void release(PreparedTarget preparedTarget) {
        if (preparedTarget instanceof KafkaPreparedTarget target) {
            prepared.remove(target.targetDefinition());
            target.close();
        }
    }

    @Override
    public void close() {
        prepared.values().forEach(KafkaPreparedTarget::close);
        prepared.clear();
    }

    private Map<String, Object> producerConfig(
            BootstrapServers bootstrap, ConnectorContext context) {
        Map<String, Object> config = common(bootstrap, context, "producer");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        // Every replica must have the record before the publish is called done:
        // a scenario about lost messages cannot itself lose one silently.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // Retries belong to the scenario's retry policy, which reports them.
        config.put(ProducerConfig.RETRIES_CONFIG, 0);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, KafkaTimeouts.publish(context));
        return config;
    }

    private Map<String, Object> consumerConfig(
            BootstrapServers bootstrap, ConnectorContext context) {
        Map<String, Object> config = common(bootstrap, context, "consumer");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        // Partitions are assigned, never subscribed, and nothing is committed:
        // the run leaves no group state on the broker to clean up.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "faultora-" + runToken);
        // Every read seeks explicitly, so there is no position to reset to.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return config;
    }

    private Map<String, Object> common(
            BootstrapServers bootstrap, ConnectorContext context, String role) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap.value());
        config.put(CommonClientConfigs.CLIENT_ID_CONFIG,
                "faultora-" + runToken + "-" + role);
        long connectMs = context == null ? 0 : context.connectTimeoutMs();
        if (connectMs > 0) {
            config.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, connectMs);
        }
        // Operator-supplied Kafka settings — TLS, SASL — pass through untouched.
        if (context != null && context.config() != null) {
            context.config().forEach((key, value) -> {
                if (key != null && key.startsWith(PROTOCOL + ".") && value != null) {
                    config.put(key.substring(PROTOCOL.length() + 1), value);
                }
            });
        }
        return config;
    }
}
