package dev.faultora.runner.protocol;

import java.util.List;
import java.util.Set;

/**
 * What a runner says about itself when it dials out.
 * <p>
 * Registration is where a dispatch can be refused before anything has been
 * sent: a runner that speaks no version in common, or that lacks an extension
 * the work will need, should learn that here rather than three messages later.
 *
 * @param runnerId      a stable name for this runner, chosen by its operator
 * @param agentVersion  the Faultora build the runner is
 * @param protocols     protocol versions it speaks, its preference first
 * @param capabilities  the extension names it has — connectors, fault
 *                      providers, assertion types — so a dispatcher can tell
 *                      before dispatching that a scenario would not run
 */
public record Registration(
        String runnerId,
        String agentVersion,
        List<String> protocols,
        Set<String> capabilities
) {
    public Registration {
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
