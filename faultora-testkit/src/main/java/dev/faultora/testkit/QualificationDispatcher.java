package dev.faultora.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Progress;
import dev.faultora.runner.protocol.ProtocolVersion;
import dev.faultora.runner.protocol.Registration;
import dev.faultora.runner.protocol.Session;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Something for a runner to dial, so the runner can be qualified.
 * <p>
 * <b>This is not a controller and must not become one.</b> The controller is
 * 2.0; what 0.9 needs is the smallest counterpart that can hand out a dispatch
 * and collect what comes back, so that the runner-facing protocol can be run
 * end to end against something. ADR-020 says plainly which half is frozen at
 * 1.0 — the protocol is, this is not — and it lives in the test kit and carries
 * a name that says so, because a module called {@code controller} is a module
 * somebody eventually freezes.
 * <p>
 * It answers four things: who may register, what work there is, where the
 * journal has got to, and what became of the run. Nothing else.
 */
public final class QualificationDispatcher implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpsServer server;
    private final ExecutorService executor;
    private final QualificationDeliveryStore delivery;
    private final ConcurrentMap<String, QualificationDeliveryStore.Peer> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean loseTerminalAck = new AtomicBoolean();
    private final AtomicReference<UnaryOperator<DurableDelivery.Ack>> changeProgressAck = new AtomicReference<>();
    private final AtomicReference<Dispatch> pending = new AtomicReference<>();
    private final ConcurrentMap<String, List<String>> journals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> outcomes = new ConcurrentHashMap<>();
    private final List<Registration> registrations =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile boolean extending = true;
    private volatile long leaseTtlMs = 5_000;

    /**
     * Listen on a free port, requiring a client certificate.
     * <p>
     * The runner dials in, so this is the side that listens — outside the
     * private network, which is the whole point of the arrangement.
     */
    public QualificationDispatcher(SSLContext context) throws IOException {
        this(context, "127.0.0.1");
    }

    /**
     * Listen on a free port of a named interface.
     * <p>
     * Loopback everywhere except one case: a runner inside a container reaches
     * its host from another network namespace, and a dispatcher bound to
     * loopback is unreachable from there. Nothing about the arrangement
     * changes — the runner still dials out — only which interface the far side
     * happens to be listening on.
     */
    public QualificationDispatcher(SSLContext context, String bindHost) throws IOException {
        this(context, bindHost, 0, null);
    }

    /** Opt-in receiver for qualifying durable delivery across process recreation on the same route. */
    public QualificationDispatcher(SSLContext context, Path deliveryDirectory, int port) throws IOException {
        this(context, "127.0.0.1", port, new QualificationDeliveryStore(deliveryDirectory));
    }

    private QualificationDispatcher(SSLContext context, String bindHost, int port,
                                    QualificationDeliveryStore delivery) throws IOException {
        this.delivery = delivery;
        try {
            server = HttpsServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (IOException unavailable) {
            if (delivery != null) delivery.close();
            throw unavailable;
        }
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/runner/register", this::register);
        server.createContext("/runner/work", this::work);
        server.createContext("/runner/progress", this::progress);
        server.createContext("/runner/heartbeat", this::heartbeat);
        server.createContext("/runner/result", this::result);
        if (delivery != null) {
            server.createContext("/runner/v2/progress", this::durableProgress);
            server.createContext("/runner/v2/result", this::durableResult);
        }
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
    }

    /** Where a runner dials. */
    public String address() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The port, for a runner that reaches this host under another name. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Offer this dispatch to the next runner that asks. */
    public void offer(Dispatch dispatch) {
        pending.set(dispatch);
    }

    /** The journal lines this dispatcher has been told about, in order. */
    public List<String> journalOf(String runId) {
        return List.copyOf(journals.getOrDefault(runId, List.of()));
    }

    /** What the runner said became of a run, or null while it is still going. */
    public String outcomeOf(String runId) {
        return outcomes.get(runId);
    }

    public DurableDelivery.Terminal durableTerminalOf(String runId) throws IOException {
        return delivery.terminalOf(runId);
    }

    public List<String> durableJournalOf(String runId) throws IOException {
        return delivery.journalOf(runId);
    }

    /** Close the next accepted terminal response after persistence, before sending its ACK. */
    public void loseNextDurableTerminalAck() { loseTerminalAck.set(true); }

    /** Replace one progress ACK when qualifying a malformed or conflicting peer. */
    public void changeNextDurableProgressAck(UnaryOperator<DurableDelivery.Ack> change) {
        changeProgressAck.set(change);
    }

    /** Every registration this dispatcher has answered. */
    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    private void register(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(Registration.MAX_JSON_BYTES + 1);
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        List<String> supported = delivery == null ? ProtocolVersion.SUPPORTED
                : List.of(ProtocolVersion.DURABLE_DELIVERY);
        Session session = Session.answerJson(body.length > Registration.MAX_JSON_BYTES ? null : json,
                UUID.randomUUID().toString(), supported);
        if (session.isAccepted()) {
            Registration registration = MAPPER.readerFor(Registration.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
            if (delivery != null) {
                var peer = new QualificationDeliveryStore.Peer(registration.runnerId(), certificateDigest(exchange));
                sessions.put(session.sessionId(), peer);
            }
            registrations.add(registration);
        }
        respond(exchange, session.isAccepted() ? 200 : 409,
                MAPPER.writeValueAsString(session));
    }

    /**
     * Hand out the pending dispatch, or say there is none.
     * <p>
     * Answering immediately rather than holding the connection: a real control
     * plane long-polls, and a qualification harness that did would only make
     * its tests slower. The runner's side of the poll is the half that has to
     * be right, and it is the half being qualified.
     */
    private void work(HttpExchange exchange) throws IOException {
        QualificationDeliveryStore.Peer registered = delivery == null ? null : registeredPeer(exchange);
        if (delivery != null && registered == null) {
            respond(exchange, 403, "");
            return;
        }
        Dispatch dispatch = pending.getAndSet(null);
        if (dispatch == null) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (registered != null) {
            try {
                delivery.authorize(registered, dispatch);
            } catch (QualificationDeliveryStore.Conflict conflict) {
                respond(exchange, 409, "");
                return;
            } catch (IOException unavailable) {
                pending.compareAndSet(null, dispatch);
                respond(exchange, 503, "");
                return;
            }
        }
        respond(exchange, 200, MAPPER.writeValueAsString(dispatch));
    }

    private QualificationDeliveryStore.Peer registeredPeer(HttpExchange exchange) throws IOException {
        String session = queryValue(exchange, "session");
        QualificationDeliveryStore.Peer registered = session == null ? null : sessions.get(session);
        return registered != null && registered.certificateDigest().equals(certificateDigest(exchange))
                ? registered : null;
    }

    private static String certificateDigest(HttpExchange exchange) throws IOException {
        try {
            return ContentDigest.sha256Uri(((HttpsExchange) exchange).getSSLSession()
                    .getPeerCertificates()[0].getEncoded());
        } catch (CertificateEncodingException unavailable) {
            throw new IOException("DURABLE_RECEIVER_PEER_UNAVAILABLE");
        }
    }

    private void durableProgress(HttpExchange exchange) throws IOException {
        deliver(exchange, DurableDelivery.Progress.class, (peer, progress) -> {
            var ack = delivery.progress(peer, progress);
            var change = changeProgressAck.getAndSet(null);
            return change == null ? ack : change.apply(ack);
        });
    }

    private void durableResult(HttpExchange exchange) throws IOException {
        deliver(exchange, DurableDelivery.Terminal.class, (peer, terminal) -> {
            var ack = delivery.terminal(peer, terminal);
            return loseTerminalAck.getAndSet(false) ? null : ack;
        });
    }

    @FunctionalInterface
    private interface Accept<T> {
        DurableDelivery.Ack accept(QualificationDeliveryStore.Peer peer, T message) throws IOException;
    }

    private <T> void deliver(HttpExchange exchange, Class<T> type, Accept<T> accept) throws IOException {
        QualificationDeliveryStore.Peer registered = registeredPeer(exchange);
        if (registered == null || !"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 403, "");
            return;
        }
        T message;
        try {
            message = DurableDelivery.decode(exchange.getRequestBody().readNBytes(DurableDelivery.MAX_REQUEST_BYTES + 1),
                    type, DurableDelivery.MAX_REQUEST_BYTES);
        } catch (IOException malformed) {
            respond(exchange, 400, "");
            return;
        }
        DurableDelivery.Ack ack;
        try {
            ack = accept.accept(registered, message);
        } catch (QualificationDeliveryStore.Conflict conflict) {
            respond(exchange, 409, "");
            return;
        } catch (IOException unavailable) {
            respond(exchange, 503, "");
            return;
        }
        if (ack == null) exchange.close();
        else respond(exchange, 200, MAPPER.writeValueAsString(ack));
    }

    /**
     * Take journal lines and answer with the position now held.
     * <p>
     * Two rules, and the second is the one worth writing down. A batch that
     * starts <em>before</em> what is already here is a re-send after a
     * disconnection: the overlap is dropped rather than appended, which is what
     * makes at-least-once delivery safe to rely on. A batch that starts
     * <em>beyond</em> it is a hole — lines nobody will ever send again — and it
     * is refused rather than closed up, because a journal silently missing its
     * middle is worse than a delivery that failed loudly. ADR-020 records both,
     * since a real controller has to implement the same pair.
     */
    private void progress(HttpExchange exchange) throws IOException {
        if (delivery != null) {
            respond(exchange, 403, "");
            return;
        }
        Progress progress = MAPPER.readValue(exchange.getRequestBody(), Progress.class);
        List<String> journal = journals.computeIfAbsent(
                progress.runId(), runId -> java.util.Collections.synchronizedList(
                        new ArrayList<>()));
        synchronized (journal) {
            if (progress.fromPosition() > journal.size()) {
                respond(exchange, 409, "run '" + progress.runId() + "' is at "
                        + journal.size() + " here and this batch starts at "
                        + progress.fromPosition() + "; the lines between would be lost");
                return;
            }
            long position = progress.fromPosition();
            for (String line : progress.eventLines()) {
                if (position >= journal.size()) {
                    journal.add(line);
                }
                position++;
            }
            respond(exchange, 200, String.valueOf(journal.size()));
        }
    }

    /**
     * Extend a run's permission, while this side is willing.
     * <p>
     * Willingness is a switch a test can throw, because the interesting
     * question is not what happens when a dispatcher says yes — it is what
     * happens when it stops saying anything at all, which a test arranges by
     * closing this server outright.
     */
    private void heartbeat(HttpExchange exchange) throws IOException {
        if (!extending) {
            respond(exchange, 410, "");
            return;
        }
        respond(exchange, 200, MAPPER.writeValueAsString(
                new Lease(System.currentTimeMillis(), leaseTtlMs, leaseTtlMs / 4)));
    }

    /** Stop granting extensions, without closing the connection. */
    public void stopExtendingLeases() {
        extending = false;
    }

    /** How long each extension is worth. */
    public void grantLeasesOf(long ttlMs) {
        leaseTtlMs = ttlMs;
    }

    private void result(HttpExchange exchange) throws IOException {
        if (delivery != null) {
            respond(exchange, 403, "");
            return;
        }
        String runId = queryValue(exchange, "run");
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        outcomes.put(runId == null ? "unknown" : runId, body);
        respond(exchange, 200, "");
    }

    private static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        if (delivery != null) {
            try { delivery.close(); }
            catch (IOException failed) { throw new java.io.UncheckedIOException("Receiver storage close failed", failed); }
        }
    }
}
