package dev.faultora.runtime;

import dev.faultora.engine.plan.ExecutionPlan;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.model.ScenarioMetadata;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEnvironmentTest {
    private static final ExtensionPolicy EXTENSIONS = new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    @Test
    void failureOpeningTheFirstConnectorPreservesTheCause() {
        RuntimeException failure = new IllegalStateException("synthetic open failure");
        assertThatThrownBy(() -> RunEnvironment.open(plan("http"), Map.of(), EXTENSIONS,
                protocol -> {
                    throw failure;
                })).isSameAs(failure);
    }

    @Test
    void failureOpeningALaterConnectorClosesEverythingAlreadyOpened() {
        List<String> closed = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("synthetic JDBC failure");
        assertThatThrownBy(() -> RunEnvironment.open(plan("http", "kafka", "jdbc"), Map.of(), EXTENSIONS,
                protocol -> {
                    if (protocol.equals("jdbc")) {
                        throw failure;
                    }
                    return connector(protocol, closed, null);
                })).isSameAs(failure);
        assertThat(closed).containsExactly("kafka", "http");
    }

    @Test
    void aCloseFailureDoesNotHideTheOpeningFailureOrLeakEarlierConnectors() {
        List<String> closed = new ArrayList<>();
        RuntimeException opening = new IllegalStateException("synthetic open failure");
        RuntimeException closing = new IllegalStateException("synthetic close failure");
        assertThatThrownBy(() -> RunEnvironment.open(plan("http", "kafka", "jdbc"), Map.of(), EXTENSIONS,
                protocol -> {
                    if (protocol.equals("jdbc")) {
                        throw opening;
                    }
                    return connector(protocol, closed, protocol.equals("kafka") ? closing : null);
                })).isSameAs(opening);
        assertThat(closed).containsExactly("kafka", "http");
        assertThat(opening.getSuppressed()).containsExactly(closing);
    }

    @Test
    void engineConstructionFailureAlsoClosesAllAcquiredConnectors() {
        List<String> closed = new ArrayList<>();
        Map<String, FaultProvider> invalidProviders = new HashMap<>();
        invalidProviders.put("synthetic", null);
        assertThatThrownBy(() -> RunEnvironment.open(plan("http", "kafka", "jdbc"), invalidProviders, EXTENSIONS,
                protocol -> connector(protocol, closed, null))).isInstanceOf(NullPointerException.class);
        assertThat(closed).containsExactly("jdbc", "kafka", "http");
    }

    @Test
    void anInitializationErrorStillReleasesPreviouslyOpenedClients() {
        List<String> closed = new ArrayList<>();
        LinkageError failure = new LinkageError("synthetic initialization failure");
        assertThatThrownBy(() -> RunEnvironment.open(plan("http", "kafka"), Map.of(), EXTENSIONS,
                protocol -> {
                    if (protocol.equals("kafka")) {
                        throw failure;
                    }
                    return connector(protocol, closed, null);
                })).isSameAs(failure);
        assertThat(closed).containsExactly("http");
    }

    @Test
    void successfulOpeningKeepsClientsAliveUntilTheEnvironmentCloses() {
        List<String> opened = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        try (RunEnvironment environment = RunEnvironment.open(plan("http", "jdbc"), Map.of(), EXTENSIONS,
                protocol -> {
                    opened.add(protocol);
                    return connector(protocol, closed, null);
                })) {
            assertThat(environment.engine()).isNotNull();
            assertThat(opened).containsExactly("http", "jdbc");
            assertThat(closed).isEmpty();
        }
        assertThat(closed).containsExactlyInAnyOrder("http", "jdbc");
    }

    @Test
    void unsupportedPolicyIsRejectedBeforeAnyClientIsOpened() {
        AtomicInteger opened = new AtomicInteger();
        ExtensionPolicy isolation = new ExtensionPolicy(Set.of(), true, 0, Set.of(), Set.of());
        assertThatThrownBy(() -> RunEnvironment.open(plan("http"), Map.of(), isolation, protocol -> {
            opened.incrementAndGet();
            return connector(protocol, new ArrayList<>(), null);
        })).isInstanceOf(UnenforceablePolicy.class);
        assertThat(opened.get()).isZero();
    }

    private static ExecutionPlan plan(String... protocols) {
        ScenarioDocument scenario = new ScenarioDocument("faultora.dev/v1", "Scenario",
                new ScenarioMetadata("environment", null, Map.of(), Map.of()), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        List<ProtocolId> declared = java.util.Arrays.stream(protocols).map(ProtocolId::new).toList();
        ApiCatalog catalog = new ApiCatalog(new CatalogVersion("synthetic"), List.of(new TargetDefinition(
                new TargetId("test"), "Test", "http://127.0.0.1:1", declared, List.of(), Map.of())),
                List.of(), Map.of(), Map.of(), List.of());
        return ExecutionPlan.builder().runId(new RunId("synthetic"))
                .scenario(scenario).catalog(catalog)
                .targetPolicy(new TargetPolicy(Set.of(), Set.of(), 100, 1, 5000, 1024, Set.of(), Set.of()))
                .build();
    }

    private static Connector connector(String protocol, List<String> closed, RuntimeException closeFailure) {
        return new Connector() {
            @Override
            public ProtocolId protocol() {
                return new ProtocolId(protocol);
            }

            @Override
            public Set<String> capabilities() {
                return Set.of();
            }

            @Override
            public PreparedTarget prepare(TargetDefinition target, ConnectorContext context) {
                return () -> target;
            }

            @Override
            public OperationResult execute(PreparedTarget target, OperationDefinition operation,
                                           Map<String, Object> inputs, ConnectorContext context) {
                throw new AssertionError("No operation belongs to environment initialization");
            }

            @Override
            public void release(PreparedTarget target) {
            }

            @Override
            public void close() {
                closed.add(protocol);
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        };
    }
}
