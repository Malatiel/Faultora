package dev.faultora.spi.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows an observation returned.
 * <p>
 * This is the protocol-neutral shape of tabular evidence, as
 * {@link MessageEvidence} is for messages: a connector for any store produces
 * it, the assertions about rows consume it, and the engine journals it without
 * knowing which store it came from.
 * <p>
 * {@link #truncated} is the field that keeps an assertion honest. A row limit
 * is applied at the driver, so a query that would have returned ten rows can
 * return the three the policy allowed — and a scenario asserting "three rows"
 * against a truncated ten must not read as satisfied. An assertion that counts
 * rows has to consult it; one that does not is asserting about the limit rather
 * than about the data.
 *
 * @param columns   column names, in the order the query returned them
 * @param rows      the rows kept, each keyed by column name
 * @param truncated whether the row limit stopped the read before the result did
 */
public record TableEvidence(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated
) {
    /** Protocol-evidence key under which observed rows are published. */
    public static final String OBSERVED = "rows";

    public TableEvidence {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : copyRows(rows);
    }

    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copied = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copied.add(row == null
                    ? Map.of() : java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(row)));
        }
        return List.copyOf(copied);
    }

    /**
     * The rows an operation observed.
     *
     * @return the rows, or null when the evidence has none — which is what
     *         evidence from a non-tabular protocol looks like
     */
    public static TableEvidence observedIn(Map<String, Object> protocolEvidence) {
        return protocolEvidence != null
                && protocolEvidence.get(OBSERVED) instanceof TableEvidence table
                ? table : null;
    }

    /** How many rows were kept. */
    public int rowCount() {
        return rows.size();
    }

    /**
     * One column's values, in row order.
     *
     * @return the values, with a null for a row that has no such column
     */
    public List<Object> column(String name) {
        List<Object> values = new ArrayList<>(rows.size());
        rows.forEach(row -> values.add(row.get(name)));
        return List.copyOf(values);
    }
}
