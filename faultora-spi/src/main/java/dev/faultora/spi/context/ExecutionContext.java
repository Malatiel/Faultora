package dev.faultora.spi.context;

import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.model.security.TargetPolicy;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Context provided to extensions during execution.
 * Extensions must not retain references beyond their lifecycle.
 *
 * @param workDir           working directory for this run
 * @param secretResolver    function to resolve secret handle IDs to SecretHandle
 * @param targetPolicy      effective target policy
 * @param evidencePolicy    effective evidence policy
 * @param extensionPolicy   effective extension policy
 * @param config            resolved non-secret configuration
 */
public record ExecutionContext(
        Path workDir,
        Function<String, SecretHandle> secretResolver,
        TargetPolicy targetPolicy,
        EvidencePolicy evidencePolicy,
        ExtensionPolicy extensionPolicy,
        Map<String, Object> config
) {}
