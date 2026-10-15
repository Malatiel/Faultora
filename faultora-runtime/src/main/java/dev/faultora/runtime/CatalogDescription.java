package dev.faultora.runtime;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.security.TargetPolicy;

import java.util.List;

/** Catalog topology suitable for adapters; excludes URLs, schemas, examples and input values. */
public record CatalogDescription(List<Target> targets, List<Operation> operations) {
    public CatalogDescription {
        targets = List.copyOf(targets);
        operations = List.copyOf(operations);
    }

    public record Target(String id, List<String> protocols) {
        public Target {
            protocols = List.copyOf(protocols);
        }
    }

    public record Operation(String id, String target, String protocol, String safety,
                            boolean permitted) { }

    public static CatalogDescription describe(ApiCatalog catalog, TargetPolicy policy) {
        return new CatalogDescription(
                catalog.targets().stream().map(target -> new Target(target.id().value(),
                        target.protocols().stream().map(protocol -> protocol.value()).sorted().toList()))
                        .toList(),
                catalog.operations().stream().map(operation -> new Operation(
                        operation.id().value(), operation.target().value(), operation.protocol().value(),
                        operation.safety().name(),
                        (policy.allowedTargets().isEmpty()
                                || policy.allowedTargets().contains(operation.target()))
                                && (policy.allowedOperationClasses().isEmpty()
                                        || policy.allowedOperationClasses().contains(operation.safety()))
                                && RunEnvironment.PROTOCOLS_SPOKEN.contains(operation.protocol().value())))
                        .toList());
    }
}
