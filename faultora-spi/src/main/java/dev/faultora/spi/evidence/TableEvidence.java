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
 * {@link #valuesWithheld} is the other. An evidence policy that captures no
 * bodies keeps how many rows an observation returned — a count is not content —
 * and keeps none of the values. An assertion reading a value then has nothing
 * to read, and must say so rather than compare against a blank.
 *
 * @param columns        column names, in the order the query returned them
 * @param rows           the rows kept, each keyed by column name
 * @param truncated      whether the row limit stopped the read before the
 *                       result did
 * @param valuesWithheld whether the evidence policy kept the counts and
 *                       withheld the values
 */
public record TableEvidence(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated,
        boolean valuesWithheld
) {
    /** Protocol-evidence key under which observed rows are published. */
    public static final String OBSERVED = "rows";

    /** A separator that ends a field of the canonical form. */
    private static final char FIELD = '\u001F';

    /** A separator that ends a record of the canonical form. */
    private static final char RECORD = '\u001E';

    /** What escapes a separator that occurs inside a value. */
    private static final char ESCAPE = '\u001B';

    public TableEvidence {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : copyRows(rows);
    }

    /** Rows whose values the policy permitted keeping. */
    public TableEvidence(List<String> columns, List<Map<String, Object>> rows,
            boolean truncated) {
        this(columns, rows, truncated, false);
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
     * A stable rendering of this table, for digesting.
     * <p>
     * {@code Map.toString} is not one. It renders whichever order a map
     * happens to iterate in, cannot tell a null apart from the text
     * {@code "null"}, and cannot tell one value containing its separator apart
     * from two values. A digest that changes when the rows did not — or stays
     * the same when they did — is worse than no digest, because a report
     * compares it between runs.
     * <p>
     * So values are read by column name in column order, a null is a distinct
     * symbol rather than a word, and a separator inside a value is escaped.
     */
    public String canonicalForm() {
        StringBuilder canonical = new StringBuilder();
        columns.forEach(column -> canonical.append(escaped(column)).append(FIELD));
        canonical.append(RECORD);
        for (Map<String, Object> row : rows) {
            for (String column : columns) {
                Object value = row.get(column);
                // No value renders as an escaped 'N', which no value can
                // produce: a literal escape inside one is doubled.
                canonical.append(value == null
                                ? String.valueOf(ESCAPE) + 'N'
                                : escaped(String.valueOf(value)))
                        .append(FIELD);
            }
            canonical.append(RECORD);
        }
        return canonical.toString();
    }

    /** A value with the separators it contains made unambiguous. */
    private static String escaped(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == FIELD || character == RECORD || character == ESCAPE) {
                escaped.append(ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * One column's values, in row order.
     *
     * @return the values, with a null for a row that has no such column
     */
    public List<Object> column(String name) {
        List<Object> values = new ArrayList<>(rows.size());
        rows.forEach(row -> values.add(row.get(name)));
        // Not List.copyOf: a column holding a SQL NULL has a null in it, and
        // that throws. A column of nulls is what an unset column looks like.
        return java.util.Collections.unmodifiableList(values);
    }
}
