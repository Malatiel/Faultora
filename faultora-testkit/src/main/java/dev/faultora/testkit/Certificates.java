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
