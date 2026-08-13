package dev.faultora.runtime;

import dev.faultora.model.security.ExtensionPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control that does not exist has to say so.
 * <p>
 * {@code ExtensionPolicy} has described process isolation, a memory ceiling, a
 * network allowlist and a set of permitted secret handles since it was written,
 * and nothing has ever read any of them. An operator who set one had a run
 * proceed exactly as if they had not — which is the worst version of this,
 * because the configuration is right there in the file they are looking at.
 * <p>
 * Both halves, as everywhere: a policy asking for one of them is refused and
 * names it, and a policy asking for none of them runs.
 */
class ExtensionPolicyEnforcementTest {

    /** What the CLI and the runner both build today. */
    private static ExtensionPolicy asking(
            boolean isolation, int memoryMb, Set<String> hosts, Set<String> secrets) {
        return new ExtensionPolicy(Set.of(), isolation, memoryMb, hosts, secrets);
    }

    @Test
    void aPolicyThatAsksForNothingUnenforceableIsAccepted() {
        assertThat(ExtensionRegistry.notYetEnforced(
                asking(false, 0, Set.of(), Set.of())))
                .as("what every run configures today, and it must keep working")
                .isEmpty();
    }

    @Test
    void askingForProcessIsolationIsRefusedUntilThereIsSome() {
        assertThat(ExtensionRegistry.notYetEnforced(asking(true, 0, Set.of(), Set.of())))
                .singleElement().asString().contains("process isolation");
    }

    @Test
    void theThreeLimitsThatNeedAnotherProcessSayTheyAreNotEnforced() {
        // A heap ceiling, a network allowlist and a secret allowlist are all
        // unenforceable while the extension shares this JVM's heap, sockets and
        // resolver. Approximating them in-process would be a control that
        // reports success and prevents nothing.
        assertThat(ExtensionRegistry.notYetEnforced(asking(false, 512, Set.of(), Set.of())))
                .singleElement().asString().contains("512MB");
        assertThat(ExtensionRegistry.notYetEnforced(
                asking(false, 0, Set.of("api.example.com"), Set.of())))
                .singleElement().asString().contains("network allowlist");
        assertThat(ExtensionRegistry.notYetEnforced(
                asking(false, 0, Set.of(), Set.of("api-token"))))
                .singleElement().asString().contains("secret allowlist");
    }

    @Test
    void everythingAskedForIsNamed() {
        // An operator who set four of these should not have to fix them one
        // run at a time.
        assertThat(ExtensionRegistry.notYetEnforced(
                asking(true, 256, Set.of("api.example.com"), Set.of("api-token"))))
                .hasSize(4);
    }

    @Test
    void aRunDoesNotStartUnderAPolicyThisBuildCannotKeep() {
        // The refusal is at the composition root both the CLI and the runner
        // go through, so neither can forget it — and the message says where to
        // read about which control arrives when.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                RunEnvironment.open(null, java.util.Map.of(),
                        asking(true, 0, Set.of(), Set.of()), false))
                .isInstanceOf(UnenforceablePolicy.class)
                .hasMessageContaining("process isolation")
                .hasMessageContaining("ADR-023");
    }
}
