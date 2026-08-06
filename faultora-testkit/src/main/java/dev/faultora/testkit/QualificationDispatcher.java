package dev.faultora.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Progress;
import dev.faultora.runner.protocol.Registration;
import dev.faultora.runner.protocol.Session;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Dispatch> pending = new AtomicReference<>();
    private final ConcurrentMap<String, List<String>> journals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> outcomes = new ConcurrentHashMap<>();
    private final List<Registration> registrations =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * Listen on a free port, requiring a client certificate.
     * <p>
     * The runner dials in, so this is the side that listens — outside the
     * private network, which is the whole point of the arrangement.
     */
    public QualificationDispatcher(SSLContext context) throws IOException {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        server.createContext("/runner/result", this::result);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    /** Where a runner dials. */
    public String address() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
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

    /** Every registration this dispatcher has answered. */
    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    private void register(HttpExchange exchange) throws IOException {
        Registration registration =
                MAPPER.readValue(exchange.getRequestBody(), Registration.class);
        registrations.add(registration);
        Session session = Session.answer(registration, UUID.randomUUID().toString());
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
        Dispatch dispatch = pending.getAndSet(null);
        if (dispatch == null) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        respond(exchange, 200, MAPPER.writeValueAsString(dispatch));
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

    private void result(HttpExchange exchange) throws IOException {
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
    }
}
