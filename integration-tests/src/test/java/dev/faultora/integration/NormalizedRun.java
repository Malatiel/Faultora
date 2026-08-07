package dev.faultora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A run journal reduced to what two runs of the same scenario must agree on.
 * <p>
 * The exit gate asks that local and runner modes produce the same normalized
 * result model, and the temptation is to check that both passed. That check
 * cannot fail for the reason it exists: the runner once captured no evidence at
 * all, so every body assertion came back indeterminate — and a run whose
 * assertions are indeterminate still ends {@code PASSED}. Comparing the shape
 * of the journals is what makes the drift visible.
 * <p>
 * What is dropped is what two runs are entitled to differ on: the run's
 * identity, when anything happened, how long it took, and the size and digest
 * of what was captured. What is kept is what the scenario asked for — which
 * steps ran, what came back, which assertions were evaluated and how they came
 * out.
 * <p>
 * The events are compared as a multiset rather than a sequence, because
 * independent steps run concurrently and their interleaving is not a promise
 * the engine makes.
 */
record NormalizedRun(List<String> events) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Fields that carry what a run did, as opposed to when it did it.
     * <p>
     * An allowlist rather than a denylist on purpose: a new event field is
     * then invisible to this comparison until somebody decides it belongs,
     * which is the safe direction. A denylist would make every added timestamp
     * a false failure in a suite nobody would trust afterwards.
     */
    private static final List<String> SIGNIFICANT = List.of(
            "eventType", "nodeId", "nodeType", "operationId", "status", "statusCode",
            "assertionType", "outcome", "evidenceType", "faultType", "targetScope",
            "rollbackStatus", "topic", "matched", "rowCount");

    /** Read a journal written by a run, whether local or remote. */
    static NormalizedRun of(Path journal) throws IOException {
        return of(Files.readAllLines(journal));
    }

    /** Reduce journal lines to their significant shape. */
    static NormalizedRun of(List<String> lines) throws IOException {
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                events.add(signatureOf(MAPPER.readTree(line)));
            }
        }
        Collections.sort(events);
        return new NormalizedRun(events);
    }

    private static String signatureOf(JsonNode event) {
        StringBuilder signature = new StringBuilder();
        for (String field : SIGNIFICANT) {
            JsonNode value = event.get(field);
            if (value != null && !value.isNull()) {
                signature.append(field).append('=').append(value.asText()).append(' ');
            }
        }
        return signature.toString().trim();
    }
}
