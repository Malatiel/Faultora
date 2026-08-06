package dev.faultora.runner;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Who this side is, and who it will talk to.
 * <p>
 * Both halves are files, and that is the decision rather than an implementation
 * detail: <b>rotating a certificate is replacing a file</b>. Nothing has to be
 * restarted and nothing has to be told, because the material is read again
 * every time a connection is opened. It is the shape a mounted secret already
 * has, and it is what makes rotation testable — a test can swap the files
 * mid-life and watch the next connection use the new identity.
 * <p>
 * How long a rotation takes to take effect is therefore how long a connection
 * lives, which is why ADR-020 bounds a long poll at thirty seconds. That number
 * is not about load.
 * <p>
 * The runner verifies the far side as strictly as the far side verifies it. A
 * runner that authenticated itself to whoever answered would take work from
 * anyone who could reach the address in its configuration.
 */
public final class TlsMaterial {

    private final Path keystore;
    private final Path truststore;
    private final Supplier<char[]> password;

    /**
     * @param keystore   this side's identity: its key and certificate
     * @param truststore the certificates this side will accept
     * @param password   supplies the store password, called once per load and
     *                   zeroed afterwards, so no long-lived copy of it exists
     */
    public TlsMaterial(Path keystore, Path truststore, Supplier<char[]> password) {
        this.keystore = keystore;
        this.truststore = truststore;
        this.password = password;
    }

    /**
     * A context built from what is on disk right now.
     * <p>
     * Read every time rather than cached. A cache would make rotation a restart,
     * and a runner inside somebody's private network is the thing least
     * convenient to restart.
     *
     * @throws TlsUnavailable when the material cannot be read or is not usable —
     *                        named, because a runner that silently fell back to
     *                        no client certificate would look like an
     *                        authentication failure at the other end
     */
    public SSLContext sslContext() {
        char[] secret = password.get();
        try {
            KeyManagerFactory keys =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load(keystore, secret), secret);

            TrustManagerFactory trust =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load(truststore, secret));

            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (TlsUnavailable unusable) {
            throw unusable;
        } catch (Exception unusable) {
            throw new TlsUnavailable(
                    "The TLS material in " + keystore + " and " + truststore
                            + " cannot be used: " + unusable.getMessage(), unusable);
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private static KeyStore load(Path path, char[] secret) {
        if (!Files.isReadable(path)) {
            throw new TlsUnavailable(
                    "No readable TLS material at " + path + ". A runner without an "
                            + "identity cannot be told apart from anything else that "
                            + "reaches the same address", null);
        }
        try (InputStream bytes = Files.newInputStream(path)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(bytes, secret);
            return store;
        } catch (Exception unreadable) {
            throw new TlsUnavailable(
                    "The TLS material at " + path + " could not be read: "
                            + unreadable.getMessage(), unreadable);
        }
    }

    /** Raised when this side cannot present or verify an identity. */
    public static final class TlsUnavailable extends RuntimeException {
        public TlsUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
