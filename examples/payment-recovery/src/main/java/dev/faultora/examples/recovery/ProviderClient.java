package dev.faultora.examples.recovery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the provider, and reports honestly when it did not hear back.
 * <p>
 * Anything other than a clean answer is {@link Outcome#UNKNOWN}: a 500, a
 * timeout, a connection that dropped. Reading a failed call as "the charge did
 * not happen" is the mistake that loses money, and it is the mistake a
 * reconciliation worker exists to make unnecessary.
 */
final class ProviderClient implements Provider {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;

    ProviderClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Outcome charge(String paymentId, long amount) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/charges"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"paymentId\":\"" + paymentId + "\",\"amount\":" + amount + "}"))
                .build();
        return send(request);
    }

    @Override
    public Outcome outcomeOf(String paymentId) {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create(baseUrl + "/charges/" + paymentId))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        return send(request);
    }

    private Outcome send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Outcome.ACCEPTED;
            }
            if (response.statusCode() == 404 || response.statusCode() == 402) {
                return Outcome.REFUSED;
            }
            return Outcome.UNKNOWN;
        } catch (IOException noAnswer) {
            return Outcome.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.UNKNOWN;
        }
    }
}
