package dev.faultora.runner;

import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.testkit.Certificates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a policy really came from the side allowed to issue one.
 * <p>
 * Every test here has both halves, because a verifier that accepts everything
 * passes the accepting half on its own — and a runner whose policy check is a
 * formality is a runner an ordinary proxy can widen.
 */
class PolicyKeysTest {

    private static final String POLICY =
            "{\"maxRequests\":1000,\"allowedFaultTypes\":[\"http-latency\"]}";

    @TempDir
    Path directory;

    private SignedPolicy signedBy(Certificates.Identity key, String algorithm, String keyId)
            throws Exception {
        return new SignedPolicy(POLICY, keyId, Certificates.sign(key, algorithm, POLICY));
    }

    @Test
    void aPolicySignedByAKeyThisRunnerHoldsIsAccepted() throws Exception {
        Certificates.Identity control = Certificates.issue(directory, "control-plane", 1);
        PolicyKeys keys = new PolicyKeys(Map.of("control-2026", control.certificate()));

        assertThat(keys.test(signedBy(control, "RSA", "control-2026"))).isTrue();
    }

    @Test
    void aPolicySignedByAnyoneElseIsNot() throws Exception {
        // Same key id, different key. Naming a trusted id has to be worth
        // nothing on its own, or the signature is decoration.
        Certificates.Identity control = Certificates.issue(directory, "control-plane", 1);
        Certificates.Identity stranger = Certificates.issue(directory, "stranger", 1);
        PolicyKeys keys = new PolicyKeys(Map.of("control-2026", control.certificate()));

        assertThat(keys.test(signedBy(stranger, "RSA", "control-2026"))).isFalse();
    }

    @Test
    void aPolicyAlteredAfterSigningIsNot() throws Exception {
        // The property mutual TLS does not give: what arrived is what was
        // issued, including through whatever terminated TLS on the way.
        Certificates.Identity control = Certificates.issue(directory, "control-plane", 1);
        PolicyKeys keys = new PolicyKeys(Map.of("control-2026", control.certificate()));
        SignedPolicy issued = signedBy(control, "RSA", "control-2026");

        SignedPolicy widened = new SignedPolicy(
                issued.policyJson().replace("1000", "100000"),
                issued.keyId(), issued.signature());

        assertThat(keys.test(issued)).isTrue();
        assertThat(keys.test(widened)).isFalse();
    }

    @Test
    void aKeyIdThisRunnerDoesNotHoldIsRefusedWithoutReadingAnything() throws Exception {
        Certificates.Identity control = Certificates.issue(directory, "control-plane", 1);
        PolicyKeys keys = new PolicyKeys(Map.of("control-2026", control.certificate()));

        assertThat(keys.test(signedBy(control, "RSA", "control-2025"))).isFalse();
    }

    @Test
    void twoKeysAreHeldAtOnceSoARolloverCanOverlap() throws Exception {
        // The reason keyId exists. With one key, replacing it is a swap two
        // systems have to make at the same moment; with both held, the new key
        // is added, then the signer moves, then the old key goes.
        Certificates.Identity outgoing = Certificates.issue(directory, "outgoing", 1);
        Certificates.Identity incoming = Certificates.issue(directory, "incoming", 1);
        PolicyKeys keys = new PolicyKeys(Map.of(
                "control-2025", outgoing.certificate(),
                "control-2026", incoming.certificate()));

        assertThat(keys.test(signedBy(outgoing, "RSA", "control-2025"))).isTrue();
        assertThat(keys.test(signedBy(incoming, "RSA", "control-2026"))).isTrue();
    }

    @Test
    void theKeyIsReadWhenItIsUsedSoRotationIsAFileSwap() throws Exception {
        // The same property the TLS material has, and for the same reason: a
        // runner inside somebody's private network is the thing least
        // convenient to restart.
        Certificates.Identity outgoing = Certificates.issue(directory, "outgoing", 1);
        Certificates.Identity incoming = Certificates.issue(directory, "incoming", 1);
        Path inUse = directory.resolve("policy-key.crt");
        Files.copy(outgoing.certificate(), inUse);
        PolicyKeys keys = new PolicyKeys(Map.of("control", inUse));

        assertThat(keys.test(signedBy(outgoing, "RSA", "control"))).isTrue();

        Files.copy(incoming.certificate(), inUse,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThat(keys.test(signedBy(incoming, "RSA", "control")))
                .as("the new key works without anything being restarted").isTrue();
        assertThat(keys.test(signedBy(outgoing, "RSA", "control")))
                .as("and the old one stops working, which is what rotating means")
                .isFalse();
    }

    @Test
    void anOperatorsChoiceOfAlgorithmIsTheirs() throws Exception {
        // The signature algorithm follows the key rather than a preference
        // written here, so a deployment that issued Ed25519 keys is not told
        // its key is unusable.
        Certificates.Identity ed = Certificates.issueKeyOf("Ed25519", directory, "modern");
        PolicyKeys keys = new PolicyKeys(Map.of("control", ed.certificate()));

        assertThat(keys.test(signedBy(ed, "Ed25519", "control"))).isTrue();
        assertThat(keys.test(new SignedPolicy(POLICY, "control", "bm90LWEtc2lnbmF0dXJl")))
                .isFalse();
    }

    @Test
    void aMissingKeyFileIsARefusalRatherThanAFailure() {
        // A runner that threw here would report a misconfigured file as an
        // unexplained failure of the run, which is the least useful thing it
        // could say to somebody who cannot reach it.
        PolicyKeys keys = new PolicyKeys(
                Map.of("control", directory.resolve("nothing-here.crt")));

        assertThat(keys.test(new SignedPolicy(POLICY, "control", "c2ln"))).isFalse();
    }
}
