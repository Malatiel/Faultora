package dev.faultora.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.FaultProvider;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * A short-lived, single-use preparation. The compiled plan and captured bindings
 * stay private; execution never recompiles or reloads the approved documents.
 */
public final class PreparedRun {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** Secret values and resolver implementation are deliberately outside the fingerprint. */
    public record Settings(
            ExpressionContext bindings, ConnectorContext connectors,
            boolean allowPrivate, Set<String> faultTypes) {
        public Settings {
            faultTypes = Set.copyOf(faultTypes);
        }
    }

    public enum Refusal { INVALID_PLAN, CHANGED, EXPIRED, ALREADY_EXECUTED }

    public static final class Refused extends IllegalStateException {
        private final Refusal reason;

        private Refused(Refusal reason) {
            super("Prepared run refused: " + reason + "; prepare a new preview");
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    private final RunPreparation.Result result;
    private final RunPreparation.Request request;
    private final Settings settings;
    private final String fingerprint;
    private final String catalogDigest;
    private final LongSupplier nanoTime;
    private final long preparedAt;
    private final long ttlNanos;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private PreparedRun(
            RunPreparation.Request request, Settings settings, Duration ttl, LongSupplier nanoTime) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Preparation lifetime must be between zero and one hour");
        }
        this.nanoTime = nanoTime;
        this.preparedAt = nanoTime.getAsLong();
        this.ttlNanos = ttl.toNanos();
        this.request = MAPPER.convertValue(MAPPER.valueToTree(request), RunPreparation.Request.class);
        this.settings = snapshot(settings);
        this.result = RunPreparation.prepare(this.request);
        if (!result.isSuccess()) {
            throw new Refused(Refusal.INVALID_PLAN);
        }
        this.catalogDigest = RunPreparation.digest(result.catalog());
        this.fingerprint = fingerprint(this.request, this.settings, catalogDigest);
    }

    public static PreparedRun prepare(RunPreparation.Request request, Settings settings, Duration ttl) {
        return new PreparedRun(request, settings, ttl, System::nanoTime);
    }

    static PreparedRun prepare(
            RunPreparation.Request request, Settings settings, Duration ttl, LongSupplier nanoTime) {
        return new PreparedRun(request, settings, ttl, nanoTime);
    }

    /** A safe projection; no mutable plan, source content or runtime input is exposed. */
    public PlanPreview preview() {
        return PlanPreview.describe(result,
                RuntimeCapabilities.describe(request.extensions(), request.targets(), settings.faultTypes()),
                request.targets());
    }

    public String catalogDigest() {
        return catalogDigest;
    }

    /** Identity of the captured inputs and effective policies, never an execution permission. */
    public String preparationDigest() {
        return fingerprint;
    }

    /** Check live caller state before allocating connectors, resolving secrets or injecting faults. */
    public void requireCurrent(RunPreparation.Request current, Settings currentSettings) {
        if (consumed.get()) {
            throw new Refused(Refusal.ALREADY_EXECUTED);
        }
        if (nanoTime.getAsLong() - preparedAt >= ttlNanos) {
            throw new Refused(Refusal.EXPIRED);
        }
        if (!fingerprint.equals(fingerprint(current, currentSettings, catalogDigest))) {
            throw new Refused(Refusal.CHANGED);
        }
    }

    public RunResult execute(
            RunPreparation.Request current, Settings currentSettings,
            Map<String, FaultProvider> faultProviders, RunJournal journal, AtomicBoolean cancellation) {
        requireCurrent(current, currentSettings);
        if (!consumed.compareAndSet(false, true)) {
            throw new Refused(Refusal.ALREADY_EXECUTED);
        }
        try (RunEnvironment environment = RunEnvironment.open(result.compilation().plan(),
                faultProviders, request.extensions(), settings.allowPrivate())) {
            Settings execution = snapshot(settings);
            return environment.engine().execute(result.compilation().plan(), journal,
                    execution.bindings(), execution.connectors(), cancellation);
        }
    }

    private static Settings snapshot(Settings settings) {
        ConnectorContext connectors = settings.connectors();
        Map<String, Object> config = MAPPER.convertValue(MAPPER.valueToTree(connectors.config()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return new Settings(settings.bindings().snapshot(), new ConnectorContext(
                connectors.evidencePolicy(), connectors.secretResolver(),
                connectors.connectTimeoutMs(), connectors.requestTimeoutMs(), connectors.totalTimeoutMs(),
                config), settings.allowPrivate(), settings.faultTypes());
    }

    private static String fingerprint(RunPreparation.Request request, Settings settings, String catalogDigest) {
        var tree = MAPPER.createObjectNode();
        tree.put("catalogDigest", catalogDigest);
        tree.set("request", MAPPER.valueToTree(request));
        tree.set("bindings", settings.bindings().tree().deepCopy());
        tree.set("secretKeys", MAPPER.valueToTree(new java.util.TreeSet<>(settings.bindings().secretKeys())));
        var policy = tree.putObject("execution");
        policy.set("evidence", MAPPER.valueToTree(settings.connectors().evidencePolicy()));
        policy.set("config", MAPPER.valueToTree(settings.connectors().config()));
        policy.put("connectTimeoutMs", settings.connectors().connectTimeoutMs());
        policy.put("requestTimeoutMs", settings.connectors().requestTimeoutMs());
        policy.put("totalTimeoutMs", settings.connectors().totalTimeoutMs());
        policy.put("allowPrivate", settings.allowPrivate());
        policy.set("faultTypes", MAPPER.valueToTree(new java.util.TreeSet<>(settings.faultTypes())));
        // Policy sets have no semantic order. Lists in documents and bindings retain theirs.
        sortSetFields(tree.path("request").path("extensions"));
        sortSetFields(tree.path("request").path("targets"));
        sortSetFields(policy.path("evidence"));
        try {
            return ContentDigest.sha256Uri("faultora.prepared.v1\n" + MAPPER.writeValueAsString(tree));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Preparation inputs cannot be fingerprinted");
        }
    }

    private static void sortSetFields(JsonNode policy) {
        Set<String> setFields = Set.of("allowedExtensions", "maxNetworkDestinations", "secretCapabilities",
                "allowedTargets", "allowedOperationClasses", "allowedFaultTypes", "allowedEnvironments",
                "headerDenylist", "contentTypeAllowlist");
        for (String name : setFields) {
            if (policy.path(name).isArray()) {
                var values = new java.util.ArrayList<JsonNode>();
                policy.path(name).forEach(values::add);
                values.sort(java.util.Comparator.comparing(JsonNode::toString));
                var sorted = MAPPER.createArrayNode();
                values.forEach(sorted::add);
                ((com.fasterxml.jackson.databind.node.ObjectNode) policy).set(name, sorted);
            }
        }
    }
}
