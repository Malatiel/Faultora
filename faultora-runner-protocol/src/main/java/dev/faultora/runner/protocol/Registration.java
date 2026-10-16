package dev.faultora.runner.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
    public static final int MAX_JSON_BYTES = 65_536;

    public Registration {
        protocols = protocols == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(protocols));
        capabilities = capabilities == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(capabilities));
    }

    boolean wellFormed() {
        return runnerId != null && runnerId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                && agentVersion != null && !agentVersion.isBlank() && agentVersion.length() <= 128
                && !protocols.isEmpty() && protocols.size() <= 16
                && new HashSet<>(protocols).size() == protocols.size()
                && protocols.stream().allMatch(version -> version != null && version.matches("[1-9][0-9]{0,8}"))
                && capabilities.size() <= 256
                && capabilities.stream().allMatch(name -> name != null
                        && name.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"));
    }
}
