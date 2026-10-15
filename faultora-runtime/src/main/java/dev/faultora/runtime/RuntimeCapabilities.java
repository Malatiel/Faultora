package dev.faultora.runtime;

import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Available contracts under one deployment's extension and target policies. */
public record RuntimeCapabilities(
        List<String> protocols, List<String> sourceTypes, List<String> assertionTypes,
        List<String> reportFormats, List<String> faultTypes,
        List<String> limitations) {
    public RuntimeCapabilities {
        protocols = List.copyOf(protocols);
        sourceTypes = List.copyOf(sourceTypes);
        assertionTypes = List.copyOf(assertionTypes);
        reportFormats = List.copyOf(reportFormats);
        faultTypes = List.copyOf(faultTypes);
        limitations = List.copyOf(limitations);
    }

    public static RuntimeCapabilities describe(
            ExtensionPolicy extensions, TargetPolicy targets,
            Set<String> availableFaultTypes) {
        ExtensionRegistry.refuseWhatIsNotEnforced(extensions);
        var sourceTypes = new TreeSet<String>();
        ExtensionRegistry.importers(extensions).forEach(importer ->
                sourceTypes.addAll(importer.supportedTypes()));
        var faults = new TreeSet<>(availableFaultTypes);
        faults.retainAll(targets.allowedFaultTypes());
        return new RuntimeCapabilities(
                RunEnvironment.PROTOCOLS_SPOKEN.stream().sorted().toList(),
                List.copyOf(sourceTypes),
                ExtensionRegistry.assertionProviders(extensions).keySet().stream().sorted().toList(),
                ExtensionRegistry.renderers(extensions).keySet().stream().sorted().toList(),
                List.copyOf(faults),
                List.of("Extension process isolation and per-extension resource controls are unavailable",
                        "Private destinations require deployment permission",
                        "Preparation does not verify destination reachability or credentials"));
    }
}
