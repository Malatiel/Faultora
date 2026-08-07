package dev.faultora.runner;

import dev.faultora.runner.protocol.SignedPolicy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The keys this runner will accept a policy from.
 * <p>
 * A dispatch carries the policy its run is permitted under, and a runner that
 * took that policy on the word of whoever sent it would let anything able to
 * reach it widen what a run may do. Mutual TLS is not the answer on its own:
 * authentication says who is speaking, and a signature says what they said and
 * that it has not been altered since — including by whatever terminates TLS in
 * the middle, which in a private network is a proxy somebody operates.
 * ADR-021 records both halves.
 * <p>
 * Three properties are deliberate:
 * <ul>
 *   <li><b>The verifying key is its own file</b>, named in the runner's
 *       configuration, and never folded into the TLS trust anchor. Terminating
 *       TLS is something an infrastructure component may legitimately be given;
 *       issuing a policy that says which faults may be injected is not.</li>
 *   <li><b>Keys are held by id</b>, so a rollover can overlap: the new key is
 *       added, dispatches signed with either are accepted, and the old one is
 *       removed afterwards. A single key would make rotation a synchronized
 *       swap across two systems.</li>
 *   <li><b>The file is read at verification</b>, like the rest of the key
 *       material, so rotating it is replacing a file rather than restarting a
 *       runner somebody has to reach into a private network to restart.</li>
 * </ul>
 * The key arrives as an X.509 certificate because that is what {@code keytool}
 * produces and what the documented procedure already exports. The signature
 * algorithm follows the key's own — an operator who issued an Ed25519 key gets
 * Ed25519, not whatever this class would have preferred.
 */
public final class PolicyKeys implements Predicate<SignedPolicy> {

    private final Map<String, Path> keysById;

    /**
     * @param keysById the verifying certificate for each key id this runner
     *                 accepts; a policy naming any other id is refused without
     *                 anything being read
     */
    public PolicyKeys(Map<String, Path> keysById) {
        this.keysById = Map.copyOf(keysById);
    }

    /** Which key ids this runner would accept a policy from. */
    public java.util.Set<String> keyIds() {
        return keysById.keySet();
    }

    /**
     * Whether this policy was signed by a key this runner holds.
     * <p>
     * False rather than thrown, for every way it can go wrong: an unreadable
     * key file, an unknown id, a signature that is not base64, one that does
     * not hold. The caller turns this into a named refusal, and a runner that
     * threw here would report a missing file as an unexplained failure of the
     * run rather than as a policy it declined.
     */
    @Override
    public boolean test(SignedPolicy policy) {
        if (policy == null || policy.policyJson() == null
                || policy.keyId() == null || policy.signature() == null) {
            return false;
        }
        Path keyFile = keysById.get(policy.keyId());
        if (keyFile == null) {
            return false;
        }
        try {
            PublicKey key = publicKeyIn(keyFile);
            Signature verifier = Signature.getInstance(signatureAlgorithmFor(key));
            verifier.initVerify(key);
            verifier.update(policy.policyJson().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(policy.signature()));
        } catch (Exception notVerified) {
            return false;
        }
    }

    private static PublicKey publicKeyIn(Path certificate) throws Exception {
        try (InputStream bytes = Files.newInputStream(certificate)) {
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(bytes).getPublicKey();
        }
    }

    /**
     * How to verify with a key of this kind.
     * <p>
     * The key decides, not this class. An EdDSA key names its own algorithm;
     * RSA and EC ones name a family and need a digest chosen, and SHA-256 is
     * the one the rest of the system hashes with.
     */
    private static String signatureAlgorithmFor(PublicKey key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> key.getAlgorithm();
        };
    }
}
