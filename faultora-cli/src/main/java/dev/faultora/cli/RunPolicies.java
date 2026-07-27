package dev.faultora.cli;

import dev.faultora.engine.exec.TargetResolver;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.faults.toxiproxy.ToxiproxyFaultProvider;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.EvidencePolicy;
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

    /** Execution policy, allowing exactly the fault types the providers offer. */
    static TargetPolicy targetPolicy(Map<String, FaultProvider> faultProviders) {
        Set<String> allowedFaultTypes = new LinkedHashSet<>();
        faultProviders.values().forEach(provider ->
                allowedFaultTypes.addAll(provider.capabilities()));
        return new TargetPolicy(
                Set.of(),
                Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING),
                MAX_REQUESTS, MAX_CONCURRENCY, MAX_DURATION_MS, MAX_PAYLOAD_BYTES,
                allowedFaultTypes, Set.of());
    }

    /**
     * Evidence policy: bodies and headers are held in memory for assertions,
     * credentials never are.
     */
    static EvidencePolicy evidencePolicy() {
        return new EvidencePolicy(
                true, true,
                Set.of("authorization", "cookie", "set-cookie", "proxy-authorization"),
                10 * 1024 * 1024, 1000, List.of(), Set.of(), "session");
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
        return new ConnectorContext(
                evidencePolicy(), secrets::resolve, 5000, 30000, 60000, config);
    }
}
