package dev.faultora.runner;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import dev.faultora.testkit.Certificates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who a runner is, and who it will talk to, over a real socket.
 * <p>
 * A runner executes scenarios against systems inside somebody's private
 * network. Everything about who may dispatch to it is a security boundary, and
 * a boundary asserted only in unit tests with a mocked handshake is a boundary
 * nobody has seen hold. So these run a TLS server, connect to it, and check
 * what happens when the certificates do not match.
 * <p>
 * The rotation test is the one M4-01 names as a deliverable: it replaces the
 * files and shows the next connection using the new identity, which is the
 * documented procedure rather than an approximation of it.
 */
class TlsMaterialTest {

    @TempDir
    Path directory;

    private HttpsServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static TlsMaterial material(Path keystore, Path truststore) {
        return new TlsMaterial(keystore, truststore,
                () -> Certificates.PASSWORD.toCharArray());
    }

    /**
     * A server that answers with the name on the client's certificate.
     * <p>
     * Which is the point: it proves the client was not merely encrypted but
     * identified, and that the identity is the one on disk right now.
     */
    private int serverRequiringClientCertificates(SSLContext context) throws IOException {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/who", exchange -> {
            String name = "unidentified";
            try {
                var peer = ((com.sun.net.httpserver.HttpsExchange) exchange)
                        .getSSLSession().getPeerPrincipal();
                name = peer.getName();
            } catch (Exception unidentified) {
                // Answered below as "unidentified", which the test refuses.
            }
            byte[] body = name.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static String ask(TlsMaterial client, int port) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .sslContext(client.sslContext())
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/who"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    @Test
    void eachSideIdentifiesTheOther() throws Exception {
        Certificates.Identity dispatcher = Certificates.issue(directory, "dispatcher", 1);
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Path dispatcherTrust = Certificates.trusting(directory, "dispatcher", runner);
        Path runnerTrust = Certificates.trusting(directory, "runner", dispatcher);

        int port = serverRequiringClientCertificates(
                material(dispatcher.keystore(), dispatcherTrust).sslContext());

        assertThat(ask(material(runner.keystore(), runnerTrust), port))
                .as("the far side knows which runner this is, not merely that it is encrypted")
                .isEqualTo("CN=runner");
    }

    @Test
    void aPeerNobodyTrustsIsTurnedAway() throws Exception {
        Certificates.Identity dispatcher = Certificates.issue(directory, "dispatcher", 1);
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity stranger = Certificates.issue(directory, "stranger", 1);
        Path dispatcherTrust = Certificates.trusting(directory, "dispatcher", runner);
        Path strangerTrust = Certificates.trusting(directory, "stranger", dispatcher);

        int port = serverRequiringClientCertificates(
                material(dispatcher.keystore(), dispatcherTrust).sslContext());

        // The stranger trusts the dispatcher, so it will start the handshake —
        // and the dispatcher does not trust it back, which is the half that
        // matters.
        assertThatThrownBy(() -> ask(material(stranger.keystore(), strangerTrust), port))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aRunnerThatWillTalkToAnyoneIsNotWhatThisBuilds() throws Exception {
        // The other direction: a runner has to verify the far side too. Here
        // the runner trusts nobody the server can present, so it refuses to
        // continue even though its own certificate would have been accepted.
        Certificates.Identity dispatcher = Certificates.issue(directory, "dispatcher", 1);
        Certificates.Identity runner = Certificates.issue(directory, "runner", 1);
        Certificates.Identity someoneElse = Certificates.issue(directory, "someone-else", 1);
        Path dispatcherTrust = Certificates.trusting(directory, "dispatcher", runner);
        Path runnerTrustsTheWrongParty =
                Certificates.trusting(directory, "runner-confused", someoneElse);

        int port = serverRequiringClientCertificates(
                material(dispatcher.keystore(), dispatcherTrust).sslContext());

        assertThatThrownBy(() ->
                ask(material(runner.keystore(), runnerTrustsTheWrongParty), port))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rotatingACertificateIsReplacingAFile() throws Exception {
        Certificates.Identity dispatcher = Certificates.issue(directory, "dispatcher", 1);
        Certificates.Identity first = Certificates.issue(directory, "runner", 1);
        Path runnerTrust = Certificates.trusting(directory, "runner", dispatcher);

        // The dispatcher trusts whichever certificate is in this file.
        Path dispatcherTrust = Certificates.trusting(directory, "dispatcher", first);
        int port = serverRequiringClientCertificates(
                material(dispatcher.keystore(), dispatcherTrust).sslContext());

        Path runnerKeystore = directory.resolve("in-use.p12");
        Files.copy(first.keystore(), runnerKeystore);
        TlsMaterial runner = material(runnerKeystore, runnerTrust);
        assertThat(ask(runner, port)).isEqualTo("CN=runner");

        // Rotation: a new key pair under the same name, written over the file
        // the runner reads. Nothing is restarted and nothing is told.
        Certificates.Identity renewed = Certificates.issue(directory, "runner-renewed", 2);
        Files.copy(renewed.keystore(), runnerKeystore,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // The far side has not been told about the new certificate yet, so it
        // refuses — which is the proof that the connection now presents it.
        assertThatThrownBy(() -> ask(runner, port))
                .as("the new material is in use immediately, without a restart")
                .isInstanceOf(IOException.class);
    }

    @Test
    void materialThatIsNotThereIsNamedRatherThanIgnored() {
        TlsMaterial missing = material(
                directory.resolve("nothing.p12"), directory.resolve("nothing-trust.p12"));

        assertThatThrownBy(missing::sslContext)
                .isInstanceOf(TlsMaterial.TlsUnavailable.class)
                .hasMessageContaining("cannot be told apart");
    }
}
