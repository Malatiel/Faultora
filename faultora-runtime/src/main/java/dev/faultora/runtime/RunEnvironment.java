package dev.faultora.runtime;

import dev.faultora.connector.http.DestinationPolicy;
import dev.faultora.connector.http.HttpConnector;
import dev.faultora.connector.jdbc.JdbcConnector;
import dev.faultora.connector.kafka.KafkaConnector;
import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.faults.local.FaultInjectingConnector;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clients a compiled plan needs, and the engine over them.
 * <p>
 * This is the composition root, and there is one of it. The CLI had it inline,
 * which was fine while the CLI was the only way to run a scenario; a runner is
 * the second way, and a second copy would be two places that have to agree
 * about which connectors exist, which one wraps the fault provider, and when a
 * broker client is worth opening. They would agree on the day they were written.
 * <p>
 * Two decisions live here and are worth stating:
 * <ul>
 *   <li><b>A client is opened only when the plan speaks its protocol.</b> An
 *       HTTP-only run should not pay for a broker connection, and would fail
 *       trying to make one where there is no broker. The compiled catalog
 *       already answers the question.</li>
 *   <li><b>Only HTTP is fault-aware.</b> The in-process fault provider acts on
 *       Faultora's own outbound requests, and the connector it wraps is the one
 *       that makes them.</li>
 * </ul>
 * Closing this closes every client it opened, which is why it is the thing a
 * caller holds rather than a map it is handed.
 */
public final class RunEnvironment implements AutoCloseable {

    /**
     * The protocols a run here can speak.
     * <p>
     * Read from the connectors below rather than typed again wherever a runner
     * has to say what it can do. What a runner advertises at registration and
     * what it can actually open are the same fact, and two spellings of one
     * fact are two things that have to be kept true.
     */
    public static final Set<String> PROTOCOLS_SPOKEN = Set.of(
            "http", KafkaConnector.PROTOCOL, JdbcConnector.PROTOCOL);

    private final LocalEngine engine;
    private final List<AutoCloseable> opened;

    private RunEnvironment(LocalEngine engine, List<AutoCloseable> opened) {
        this.engine = engine;
        this.opened = opened;
    }

    /**
     * Open what this plan needs.
     *
     * @param plan           the compiled plan, which says which protocols are spoken
     * @param faultProviders the fault providers this run may use, by name
     * @param extensions     which extensions are permitted
     * @param allowPrivate   whether destinations inside private ranges are
     *                       reachable — an operator's decision, and the reason
     *                       it is a parameter rather than a policy read here
     */
    public static RunEnvironment open(
            ExecutionPlan plan,
            Map<String, FaultProvider> faultProviders,
            ExtensionPolicy extensions,
            boolean allowPrivate
    ) {
        List<AutoCloseable> opened = new ArrayList<>();
        Map<String, Connector> connectors = new LinkedHashMap<>();

        HttpConnector http = allowPrivate
                ? new HttpConnector(DestinationPolicy.permissive())
                : new HttpConnector();
        opened.add(http);
        LocalFaultProvider localFaults = (LocalFaultProvider) faultProviders.get("local");
        connectors.put("http", localFaults == null
                ? http : new FaultInjectingConnector(http, localFaults));

        HostPolicy hosts = allowPrivate ? HostPolicy.permissive() : HostPolicy.defaultPolicy();
        if (speaks(plan, KafkaConnector.PROTOCOL)) {
            KafkaConnector kafka = new KafkaConnector(hosts);
            opened.add(kafka);
            connectors.put(KafkaConnector.PROTOCOL, kafka);
        }
        if (speaks(plan, JdbcConnector.PROTOCOL)) {
            JdbcConnector jdbc = new JdbcConnector(hosts);
            opened.add(jdbc);
            connectors.put(JdbcConnector.PROTOCOL, jdbc);
        }

        return new RunEnvironment(
                new LocalEngine(connectors,
                        ExtensionRegistry.assertionProviders(extensions), faultProviders),
                opened);
    }

    /** The engine to execute with. */
    public LocalEngine engine() {
        return engine;
    }

    /** Whether any target in the plan's catalog speaks a protocol. */
    private static boolean speaks(ExecutionPlan plan, String protocol) {
        return plan.catalog().targets().stream()
                .flatMap(target -> target.protocols().stream())
                .anyMatch(declared -> protocol.equals(declared.value()));
    }

    @Override
    public void close() {
        // Every client is closed, and one that refuses does not stop the rest:
        // a leaked broker connection is worse than a noisy shutdown.
        for (AutoCloseable client : opened) {
            try {
                client.close();
            } catch (Exception alreadyGone) {
                // Nothing left to release.
            }
        }
    }
}
