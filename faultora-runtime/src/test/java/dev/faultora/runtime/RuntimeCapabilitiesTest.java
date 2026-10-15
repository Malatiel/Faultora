package dev.faultora.runtime;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.CatalogVersion;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCapabilitiesTest {
    private static final ExtensionPolicy EXTENSIONS =
            new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());

    private static TargetPolicy policy(Set<SafetyClassification> classes, Set<String> faults) {
        return new TargetPolicy(Set.of(new TargetId("api")), classes,
                10, 2, 1000, 1024, faults, Set.of());
    }

    @Test
    void describesShippedContractsAndExplicitLimitations() {
        var capabilities = RuntimeCapabilities.describe(EXTENSIONS,
                policy(Set.of(), Set.of()), Set.of());
        assertThat(capabilities.protocols()).containsExactly("http", "jdbc", "kafka");
        assertThat(capabilities.sourceTypes()).anyMatch(type -> type.startsWith("openapi"))
                .anyMatch(type -> type.startsWith("asyncapi"))
                .anyMatch(type -> type.startsWith("observations"));
        assertThat(capabilities.assertionTypes()).contains("status", "row-count", "event-count");
        assertThat(capabilities.reportFormats()).containsExactly("console", "html", "json", "junit");
        assertThat(capabilities.limitations()).anyMatch(value -> value.contains("isolation"));
    }

    @Test
    void faultAdvertisementRequiresBothDeploymentAvailabilityAndPermission() {
        var policy = policy(Set.of(), Set.of("http-latency", "network-reset"));
        assertThat(RuntimeCapabilities.describe(EXTENSIONS, policy,
                Set.of("http-latency", "http-error")).faultTypes()).containsExactly("http-latency");
        assertThat(RuntimeCapabilities.describe(EXTENSIONS, policy, Set.of()).faultTypes()).isEmpty();
        assertThat(RuntimeCapabilities.describe(EXTENSIONS, policy(Set.of(), Set.of()),
                Set.of("http-latency")).faultTypes()).isEmpty();
    }

    @Test
    void aCapabilityResultCannotClaimUnsupportedIsolation() {
        assertThatThrownBy(() -> RuntimeCapabilities.describe(
                new ExtensionPolicy(Set.of(), true, 0, Set.of(), Set.of()),
                policy(Set.of(), Set.of()), Set.of())).isInstanceOf(UnenforceablePolicy.class);
    }

    @Test
    void catalogDescriptionMarksPolicyAndProtocolRestrictions() {
        var description = CatalogDescription.describe(catalog(),
                policy(Set.of(SafetyClassification.READ_ONLY), Set.of()));
        assertThat(description.operations()).filteredOn(CatalogDescription.Operation::permitted)
                .extracting(CatalogDescription.Operation::id).containsExactly("read");
        assertThat(description.operations()).filteredOn(operation -> !operation.permitted())
                .extracting(CatalogDescription.Operation::id).containsExactly("delete", "outside", "unsupported");
        assertThat(description.targets()).extracting(CatalogDescription.Target::id).containsExactly("api");
    }

    @Test
    void emptyOperationClassAllowlistKeepsCompilerSemantics() {
        assertThat(CatalogDescription.describe(catalog(), policy(Set.of(), Set.of())).operations())
                .filteredOn(CatalogDescription.Operation::permitted)
                .extracting(CatalogDescription.Operation::id).containsExactly("read", "delete");
    }

    private static ApiCatalog catalog() {
        return new ApiCatalog(new CatalogVersion("v1"),
                List.of(new TargetDefinition(new TargetId("api"), "API", "http://api.example.com",
                        List.of(new ProtocolId("http")), List.of(), Map.of())),
                List.of(operation("read", "api", "http", SafetyClassification.READ_ONLY),
                        operation("delete", "api", "http", SafetyClassification.DESTRUCTIVE),
                        operation("outside", "other", "http", SafetyClassification.READ_ONLY),
                        operation("unsupported", "api", "smtp", SafetyClassification.READ_ONLY)),
                Map.of(), Map.of(), List.of());
    }

    private static OperationDefinition operation(String id, String target, String protocol,
                                               SafetyClassification safety) {
        return new OperationDefinition(new OperationId(id), new ProtocolId(protocol),
                new TargetId(target), safety, Map.of(), null, Map.of(), Map.of());
    }
}
