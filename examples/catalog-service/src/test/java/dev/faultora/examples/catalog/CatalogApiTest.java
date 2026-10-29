package dev.faultora.examples.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOCUMENTS = """
            [{"key":"reference","labels":["public docs"],"attributes":{"pages":8}},
             {"key":"draft","labels":["private"],"attributes":{"pages":1}},
             {"key":"guide","labels":["public docs"],"attributes":{"pages":4}}]
            """;

    @Test
    void bulkReplacementAndEncodedLabelQueriesKeepCollectionsIndependent() throws Exception {
        try (var api = new CatalogApi(); var client = HttpClient.newHttpClient()) {
            api.start();
            assertThat(send(client, api, "alpha", "PUT", DOCUMENTS).statusCode()).isEqualTo(200);
            assertThat(send(client, api, "beta", "PUT", DOCUMENTS).statusCode()).isEqualTo(200);
            var query = send(client, api, "alpha?label=public%20docs", "GET", "");
            assertThat(query.statusCode()).isEqualTo(200);
            var values = JSON.readTree(query.body());
            assertThat(values).hasSize(2);
            assertThat(values.get(0).path("key").asText()).isEqualTo("guide");
            assertThat(values.get(1).path("attributes").path("pages").asInt()).isEqualTo(8);
            assertThat(send(client, api, "alpha", "PUT", "[]").statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(send(client, api, "alpha", "GET", "").body())).isEmpty();
            assertThat(JSON.readTree(send(client, api, "beta", "GET", "").body())).hasSize(3);
            assertThat(send(client, api, "alpha", "DELETE", "").statusCode()).isEqualTo(204);
            assertThat(send(client, api, "alpha", "GET", "").statusCode()).isEqualTo(404);
            assertThat(api.collectionCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[{\"key\":\"missing\"}]", "[] {}",
            "[{\"key\":\"a\",\"labels\":[],\"attributes\":{\"pages\":0}}]",
            "[{\"key\":\"a\",\"labels\":[],\"attributes\":{\"pages\":1.5}}]"})
    void invalidReplacementCannotPartiallyEraseTheExistingCollection(String invalid) throws Exception {
        try (var api = new CatalogApi(); var client = HttpClient.newHttpClient()) {
            api.start();
            send(client, api, "alpha", "PUT", DOCUMENTS);
            assertThat(send(client, api, "alpha", "PUT", invalid).statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(send(client, api, "alpha", "GET", "").body())).hasSize(3);
        }
    }

    @Test
    void duplicateKeysAndOversizedBodiesAreRefusedWithoutCreatingACollection() throws Exception {
        try (var api = new CatalogApi(); var client = HttpClient.newHttpClient()) {
            api.start();
            String document = "{\"key\":\"same\",\"labels\":[],\"attributes\":{\"pages\":1}}";
            assertThat(send(client, api, "alpha", "PUT", "[" + document + "," + document + "]").statusCode()).isEqualTo(400);
            var excessive = send(client, api, "alpha", "PUT", "x".repeat(65537));
            assertThat(excessive.statusCode()).isEqualTo(413);
            assertThat(excessive.body()).doesNotContain("xxx");
            assertThat(api.collectionCount()).isZero();
        }
    }

    private static HttpResponse<String> send(HttpClient client, CatalogApi api, String collectionAndQuery,
                                             String method, String body) throws Exception {
        String[] parts = collectionAndQuery.split("\\?", 2);
        String endpoint = api.baseUrl() + "/collections/" + parts[0] + "/documents"
                + (parts.length == 2 ? "?" + parts[1] : "");
        return client.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
