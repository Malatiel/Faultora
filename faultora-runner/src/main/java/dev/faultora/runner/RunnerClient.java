package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Progress;
import dev.faultora.runner.protocol.ProtocolVersion;
import dev.faultora.runner.protocol.Registration;
import dev.faultora.runner.protocol.Session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The runner's side of the wire: it dials out, and everything travels on
 * connections it opened.
 * <p>
 * That is the release gate stated as a shape rather than a promise — nothing
 * listens inside the private network, so no inbound path into it is needed.
 * Registration, asking for work, sending progress and sending a result are all
 * requests this side makes.
 * <p>
 * A poll is bounded at thirty seconds and then reopened. The number is not
 * about load: the TLS material is read when a connection opens, so the length
 * of a poll is the worst case for how long a rotated certificate takes to be
 * used (ADR-020).
 */
public final class RunnerClient {

    /** How long the runner waits for work before asking again. */
    public static final Duration POLL = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI controlPlane;
    private final TlsMaterial tls;
    private final String runnerId;
    private final String agentVersion;
    private final Set<String> capabilities;

    public RunnerClient(
            URI controlPlane,
            TlsMaterial tls,
            String runnerId,
            String agentVersion,
            Set<String> capabilities
    ) {
        this.controlPlane = controlPlane;
        this.tls = tls;
        this.runnerId = runnerId;
        this.agentVersion = agentVersion;
        this.capabilities = Set.copyOf(capabilities);
    }

    /**
     * Say who this runner is and what it can do, and learn whether the two
     * sides speak a common protocol.
     * <p>
     * A refusal here is an ordinary answer — a deployment mid-upgrade — and it
     * arrives as a {@link Session} carrying its reason rather than as a
     * connection that fails later with a parse error.
     */
    public Session register() throws IOException, InterruptedException {
        Registration registration = new Registration(
                runnerId, agentVersion, ProtocolVersion.SUPPORTED, capabilities);
        HttpResponse<String> response = send("/runner/register",
                MAPPER.writeValueAsString(registration), Duration.ofSeconds(15));
        return MAPPER.readValue(response.body(), Session.class);
    }

    /**
     * Ask for work, and wait a bounded while for it.
     *
     * @return the dispatch, or empty when the poll came back with nothing —
     *         which is ordinary and means ask again
     */
    public Optional<Dispatch> pollForWork(String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                "/runner/work?session=" + encoded(sessionId), null, POLL.plusSeconds(5));
        if (response.statusCode() == 204 || response.body() == null
                || response.body().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(response.body(), Dispatch.class));
    }

    /**
     * Send journal lines from a known position.
     *
     * @return the position the far side has now recorded, which is where the
     *         next batch starts. Delivery is at-least-once and the position is
     *         what makes re-sending harmless.
     */
    public long sendProgress(String runId, long fromPosition, List<String> eventLines)
            throws IOException, InterruptedException {
        Progress progress = new Progress(runId, fromPosition, eventLines);
        HttpResponse<String> response = send("/runner/progress",
                MAPPER.writeValueAsString(progress), Duration.ofSeconds(30));
        if (response.statusCode() != 200) {
            throw new IOException("The far side refused progress for run '" + runId
                    + "': " + response.statusCode() + " " + response.body());
        }
        String acknowledged = response.body();
        return acknowledged == null || acknowledged.isBlank()
                ? fromPosition : Long.parseLong(acknowledged.trim());
    }

    /**
     * Ask for the run's permission to be extended.
     * <p>
     * This is the only thing standing between a long run and its own lease, and
     * it is deliberately a request the runner makes: a lease it could extend by
     * itself would be a comment. When this cannot be reached — the far side is
     * gone, the network is not there — nothing is renewed, the lease runs out,
     * and the run stops on the runner's own clock. That is not the failure
     * path; it is the mechanism.
     *
     * @return the lease as re-granted, or empty when the far side is no longer
     *         willing to extend it
     */
    public Optional<Lease> heartbeat(String runId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                "/runner/heartbeat?run=" + encoded(runId), "", Duration.ofSeconds(10));
        if (response.statusCode() != 200 || response.body() == null
                || response.body().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(response.body(), Lease.class));
    }

    /** Report how a run ended, or why it never started. */
    public void sendOutcome(String runId, String outcomeJson)
            throws IOException, InterruptedException {
        send("/runner/result?run=" + encoded(runId), outcomeJson, Duration.ofSeconds(30));
    }

    /**
     * A value as it may appear in a query string.
     * <p>
     * Session ids are UUIDs today and run ids are chosen by whoever dispatches,
     * which is exactly the argument for encoding both: this is protocol code,
     * and the day an id carries an ampersand is not the day to discover that it
     * was pasted in raw.
     */
    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * One request, on a connection opened now.
     * <p>
     * A client per request rather than one held open: the TLS material is read
     * as the connection is made, so this is what lets a rotated certificate
     * take effect without anything being restarted.
     */
    private HttpResponse<String> send(String path, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .sslContext(tls.sslContext())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest.Builder request = HttpRequest
                .newBuilder(controlPlane.resolve(path))
                .timeout(timeout);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
