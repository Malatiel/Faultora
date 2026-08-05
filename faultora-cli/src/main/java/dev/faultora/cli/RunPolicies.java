package dev.faultora.cli;

import dev.faultora.engine.exec.TargetResolver;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.faults.toxiproxy.ToxiproxyFaultProvider;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.runtime.RunEvidence;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.ConnectorContext;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the policies that bound a local run.
 * <p>
 * Everything that decides what a run may reach, break, capture, or spend is
 * built here from operator-supplied options — never from the scenario. A
 * scenario can only stay within these bounds; it cannot widen them.
 */
final class RunPolicies {

    /** Requests one run may send, across retries, iterations, and polls. */
    private static final int MAX_REQUESTS = 1000;
    /** Concurrent requests a parallel group may reach. */
    private static final int MAX_CONCURRENCY = 10;
    /** Wall-clock budget of one run. */
    private static final long MAX_DURATION_MS = 300_000;
    /** Largest response body read from a target. */
    private static final long MAX_PAYLOAD_BYTES = 1_048_576;

    private RunPolicies() {
    }

    /**
     * Fault providers available to the run.
     * <p>
     * The in-process provider acts only on Faultora's own outbound requests
     * and needs no privileges, so it is always present. Network faults reach
     * real infrastructure and appear only when the operator names a Toxiproxy
     * endpoint.
     */
    static Map<String, FaultProvider> faultProviders(TestOptions options) {
        Map<String, FaultProvider> providers = new LinkedHashMap<>();
        providers.put("local", new LocalFaultProvider());
        if (options.toxiproxyUrl() != null) {
            providers.put("toxiproxy",
                    new ToxiproxyFaultProvider(URI.create(options.toxiproxyUrl())));
        }
        return providers;
    }

    /**
     * Extension policy: built-ins always, anything else only when the operator
     * named it. Resource limits and isolation belong to the out-of-process
     * plugin protocol and are not enforced here.
     */
    static ExtensionPolicy extensionPolicy(TestOptions options) {
        return new ExtensionPolicy(
                Set.copyOf(options.allowedExtensions()), false, 0, Set.of(), Set.of());
    }

    /**
     * Execution policy: the fault types the providers offer, and the operation
     * classes the operator permits.
     * <p>
     * Destructive operations are excluded unless asked for. A scenario that
     * deletes what its setup created is ordinary and supported — but deleting
     * is not something a run should be able to do because an operation
     * happened to be described in the catalog.
     */
    static TargetPolicy targetPolicy(
            TestOptions options, Map<String, FaultProvider> faultProviders) {
        Set<String> allowedFaultTypes = new LinkedHashSet<>();
        faultProviders.values().forEach(provider ->
                allowedFaultTypes.addAll(provider.capabilities()));

        Set<SafetyClassification> allowedOperations = new LinkedHashSet<>(
                Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING));
        if (options.allowDestructive()) {
            allowedOperations.add(SafetyClassification.DESTRUCTIVE);
        }
        return new TargetPolicy(
                Set.of(), allowedOperations,
                MAX_REQUESTS, MAX_CONCURRENCY, MAX_DURATION_MS, MAX_PAYLOAD_BYTES,
                allowedFaultTypes, Set.of());
    }

    /**
     * Evidence policy: bodies and headers are held in memory for assertions,
     * credentials never are.
     */
    static EvidencePolicy evidencePolicy() {
        // Shared with the runner. Two copies of this is a scenario that passes
        // here and comes back indeterminate from a private network.
        return RunEvidence.defaultPolicy();
    }

    /**
     * Connector context: deadlines, secret resolution, and the base URLs the
     * catalog's targets are bound to for this run.
     */
    static ConnectorContext connectorContext(
            TestOptions options, TargetPolicy targetPolicy, EnvironmentSecretResolver secrets) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(TargetResolver.BASE_URL, options.targetUrl());
        options.targetUrls().forEach((targetId, url) ->
                config.put(TargetResolver.BASE_URL_PREFIX + targetId, url));
        config.put("maxResponseBytes", targetPolicy.maxPayloadBytes());
        if (options.authSecretId() != null) {
            config.put("authSecretId", options.authSecretId());
        }
        if (options.databaseUser() != null) {
            config.put(dev.faultora.connector.jdbc.JdbcConnector.USER, options.databaseUser());
        }
        if (options.databaseSecretId() != null) {
            config.put(dev.faultora.connector.jdbc.JdbcConnector.SECRET_ID,
                    options.databaseSecretId());
        }
        return new ConnectorContext(
                evidencePolicy(), secrets::resolve, 5000, 30000, 60000, config);
    }
}
