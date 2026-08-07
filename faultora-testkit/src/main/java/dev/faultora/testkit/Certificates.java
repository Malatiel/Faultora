package dev.faultora.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Certificates made the way an operator makes them.
 * <p>
 * With `keytool`, from the JDK running the tests. There is no public API for
 * issuing an X.509 certificate — {@code sun.security.x509} is internal and not
 * exported — so the choices were a committed fixture, a new dependency, or the
 * tool that ships with Java. A committed fixture expires and then fails a build
 * for a reason unrelated to the change that broke it; a dependency for a test
 * is a dependency. This way the rotation test rehearses the documented
 * procedure instead of simulating it. ADR-021 records the choice.
 * <p>
 * Invoked at {@code java.home/bin/keytool} rather than from the path, so a
 * machine with a different keytool earlier on its PATH cannot decide what these
 * tests prove.
 * <p>
 * Lives here rather than beside the runner's own tests because the qualification
 * suites need it too, and a test-jar dependency to reach one class is a build
 * arrangement standing in for a module boundary.
 */
public final class Certificates {

    /** The password every store here uses; these live for one test method. */
    public static final String PASSWORD = "changeit";

    private Certificates() {
    }

    /** One identity: a keystore holding a key pair, and its certificate. */
    public record Identity(Path keystore, Path certificate) {
    }

    /**
     * Issue a self-signed identity under a name.
     *
     * @param validityDays how long it is good for — a test can issue an expired
     *                     one by asking for none
     */
    public static Identity issue(Path directory, String name, int validityDays) throws Exception {
        Path keystore = directory.resolve(name + ".p12");
        Path certificate = directory.resolve(name + ".crt");
        Files.deleteIfExists(keystore);
        Files.deleteIfExists(certificate);

        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + name, "-validity", String.valueOf(validityDays),
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", name, "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-file", certificate.toString());
        return new Identity(keystore, certificate);
    }

    /** A truststore holding exactly the certificates named. */
    public static Path trusting(Path directory, String name, Identity... trusted) throws Exception {
        Path truststore = directory.resolve(name + "-trust.p12");
        Files.deleteIfExists(truststore);
        for (int index = 0; index < trusted.length; index++) {
            keytool("-importcert", "-noprompt", "-alias", "peer-" + index,
                    "-file", trusted[index].certificate().toString(),
                    "-keystore", truststore.toString(), "-storetype", "PKCS12",
                    "-storepass", PASSWORD);
        }
        return truststore;
    }

    /**
     * Sign with an identity's private key, the way a control plane signs a
     * policy.
     * <p>
     * Here rather than in a test because the runner's side of this — verifying
     * — is only worth anything if something really signed. A fixture signature
     * would let a verifier that accepts everything pass.
     *
     * @param algorithm the key's own, as {@code keytool} was asked for it
     */
    public static String sign(Identity identity, String algorithm, String payload)
            throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream bytes = Files.newInputStream(identity.keystore())) {
            store.load(bytes, PASSWORD.toCharArray());
        }
        String alias = store.aliases().nextElement();
        java.security.PrivateKey key = (java.security.PrivateKey)
                store.getKey(alias, PASSWORD.toCharArray());
        java.security.Signature signer =
                java.security.Signature.getInstance(signatureAlgorithmFor(algorithm));
        signer.initSign(key);
        signer.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String signatureAlgorithmFor(String keyAlgorithm) {
        return switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> "EdDSA";
        };
    }

    /**
     * Issue an identity whose key is of a named algorithm.
     * <p>
     * The default {@link #issue} makes RSA keys because that is what the TLS
     * material has always used; a policy-signing key is a separate file with a
     * separate rotation, and an operator may well choose differently.
     */
    public static Identity issueKeyOf(String algorithm, Path directory, String name)
            throws Exception {
        Path keystore = directory.resolve(name + ".p12");
        Path certificate = directory.resolve(name + ".crt");
        Files.deleteIfExists(keystore);
        Files.deleteIfExists(certificate);
        keytool("-genkeypair", "-alias", name, "-keyalg", algorithm,
                "-dname", "CN=" + name, "-validity", "1",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", name, "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-file", certificate.toString());
        return new Identity(keystore, certificate);
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));

        Process keytool = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool refused: " + output);
        }
    }
}
