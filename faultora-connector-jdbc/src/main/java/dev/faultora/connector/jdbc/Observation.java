package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.OperationResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one observation and reports the rows it kept.
 * <p>
 * Every bound is applied at the driver rather than after the fact, because this
 * tool causes load on a system somebody else operates. Asking for ten thousand
 * rows and keeping a hundred is a read the target pays for in full; the row
 * limit is given to the driver, and the fetch size with it, so the rows that
 * are not kept are not fetched either.
 */
final class Observation {

    /** Rows an observation reads when the evidence policy names no limit. */
    private static final int DEFAULT_MAX_ROWS = 1000;

    /** How much a single value may contribute to the evidence, in characters. */
    private static final int MAX_CELL_CHARACTERS = 4096;

    private Observation() {
    }

    static OperationResult run(
            JdbcPreparedTarget target,
            ReadOnlyStatement statement,
            Map<String, Object> inputs,
            ConnectorContext context
    ) {
        long startedAt = System.nanoTime();
        EvidencePolicy policy = context == null || context.evidencePolicy() == null
                ? EvidencePolicy.MINIMAL : context.evidencePolicy();
        int maxRows = policy.maxRows() > 0 ? policy.maxRows() : DEFAULT_MAX_ROWS;

        String missing = firstMissingParameter(statement, inputs);
        if (missing != null) {
            return OperationResult.failure(new NormalizedError(
                    NormalizedError.ErrorCategory.VALIDATION, "PARAMETER_MISSING",
                    "The observation binds ':" + missing
                            + "', which this step does not supply", false, Map.of()),
                    elapsedMs(startedAt));
        }

        try (PreparedStatement prepared =
                     target.connection().prepareStatement(statement.prepared())) {
            // One row past the limit, so a result that was cut can say so.
            prepared.setMaxRows(maxRows + 1);
            prepared.setFetchSize(Math.min(maxRows + 1, 1000));
            prepared.setQueryTimeout((int) Math.max(1, queryTimeoutSeconds(context)));

            List<String> order = statement.bindingOrder();
            for (int index = 0; index < order.size(); index++) {
                prepared.setObject(index + 1, inputs.get(order.get(index)));
            }

            try (ResultSet rows = prepared.executeQuery()) {
                return report(rows, maxRows, startedAt);
            }
        } catch (SQLException failed) {
            return OperationResult.failure(
                    JdbcErrors.of(failed, target.url().redacted()), elapsedMs(startedAt));
        }
    }

    private static OperationResult report(
            ResultSet rows, int maxRows, long startedAt) throws SQLException {
        ResultSetMetaData metadata = rows.getMetaData();
        List<String> columns = new ArrayList<>(metadata.getColumnCount());
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            columns.add(metadata.getColumnLabel(column));
        }

        List<Map<String, Object>> kept = new ArrayList<>();
        long fetched = 0;
        boolean truncated = false;
        while (rows.next()) {
            fetched++;
            if (kept.size() >= maxRows) {
                // The row past the limit exists only to prove the result was
                // cut. An assertion counting rows has to know.
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= columns.size(); column++) {
                row.put(columns.get(column - 1), bounded(rows.getObject(column)));
            }
            kept.add(row);
        }

        TableEvidence table = new TableEvidence(columns, kept, truncated);
        return OperationResult.success(
                -1, Map.of(), null, elapsedMs(startedAt),
                JdbcConnector.evidenceOf(table, fetched));
    }

    /**
     * A value small enough to keep.
     * <p>
     * A single column can hold a document. The evidence of an observation is
     * held in memory for the whole run, so an oversized value is reported as
     * what it was rather than carried.
     */
    private static Object bounded(Object value) {
        if (value instanceof String text && text.length() > MAX_CELL_CHARACTERS) {
            return text.substring(0, MAX_CELL_CHARACTERS) + "… (" + text.length()
                    + " characters)";
        }
        if (value instanceof byte[] bytes) {
            return "binary (" + bytes.length + " bytes)";
        }
        return value;
    }

    /** The name of the first parameter the statement binds and the step omits. */
    private static String firstMissingParameter(
            ReadOnlyStatement statement, Map<String, Object> inputs) {
        for (String parameter : statement.parameters()) {
            if (!inputs.containsKey(parameter)) {
                return parameter;
            }
        }
        return null;
    }

    private static long queryTimeoutSeconds(ConnectorContext context) {
        long requestTimeoutMs = context == null ? 0 : context.requestTimeoutMs();
        return requestTimeoutMs > 0 ? requestTimeoutMs / 1000 : 30;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
