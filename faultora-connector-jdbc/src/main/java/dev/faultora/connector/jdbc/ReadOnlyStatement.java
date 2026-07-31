package dev.faultora.connector.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a declared statement is one this connector will run, and what it
 * needs bound.
 * <p>
 * This is the check that makes {@code READ_ONLY} true rather than claimed. The
 * catalog says an observation is read-only; the catalog is an operator's file
 * with SQL in it, and a file cannot classify itself honestly. So the statement
 * is read here, and anything that is not a single reading statement is refused
 * before a connection is opened.
 * <p>
 * It is deliberately strict rather than clever. A parser that understood every
 * dialect could tell a harmless statement from a harmful one in more cases, and
 * would be wrong somewhere; refusing everything that does not begin
 * {@code SELECT} or {@code WITH} is a rule an operator can hold in their head.
 * The connection is set read-only as well, and the documentation asks for
 * read-only credentials on top — because a check is one defect away from being
 * wrong, and a grant is not.
 *
 * @param sql          the statement as the operator wrote it
 * @param prepared     the same statement with positional markers, for binding
 * @param bindingOrder the parameter names in the order they are bound, with a
 *                     name repeated once per occurrence
 */
record ReadOnlyStatement(String sql, String prepared, List<String> bindingOrder) {

    /** Statement forms a reading observation may begin with. */
    private static final Set<String> READING = Set.of("select", "with");

    /**
     * Read a declared statement.
     *
     * @throws IllegalArgumentException when it is not a single reading statement
     */
    static ReadOnlyStatement of(String declared) {
        if (declared == null || declared.isBlank()) {
            throw new IllegalArgumentException("The observation declares no statement");
        }
        String sql = declared.strip();
        String opening = sql.split("[\\s(]", 2)[0].toLowerCase(Locale.ROOT);
        if (!READING.contains(opening)) {
            throw new IllegalArgumentException(
                    "An observation runs a single reading statement, and this one begins '"
                            + opening + "'. Faultora observes a database; it never writes to "
                            + "one, and the credentials it connects with should not let it");
        }
        if (endsAStatementEarly(sql)) {
            throw new IllegalArgumentException(
                    "An observation runs one statement, and this one contains a ';' that "
                            + "ends it early. Two statements in one observation would put "
                            + "the second beyond the check the first passed");
        }
        Scan scan = scan(sql);
        return new ReadOnlyStatement(sql, scan.prepared(), scan.bindings());
    }

    /** The distinct parameter names this statement binds. */
    List<String> parameters() {
        return List.copyOf(new LinkedHashSet<>(bindingOrder));
    }

    /**
     * Whether a semicolon separates statements here.
     * <p>
     * A trailing semicolon is ordinary and harmless; one with anything after it
     * begins a second statement, which is how a reading observation would carry
     * a write.
     */
    private static boolean endsAStatementEarly(String sql) {
        boolean inString = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'') {
                inString = !inString;
            } else if (character == ';' && !inString) {
                return !sql.substring(index + 1).isBlank();
            }
        }
        return false;
    }

    private record Scan(String prepared, List<String> bindings) {}

    /**
     * One pass over the statement, producing both what will be executed and
     * what will be bound into it.
     * <p>
     * A cast written {@code ::text} is not a placeholder, and neither is a colon
     * inside a string literal. Getting that wrong would bind a value into a
     * position the author did not mean, which is the failure a prepared
     * statement exists to prevent — so the two answers come from one reading of
     * the text rather than from two that could disagree.
     */
    private static Scan scan(String sql) {
        StringBuilder prepared = new StringBuilder(sql.length());
        List<String> bindings = new ArrayList<>();
        boolean inString = false;

        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'') {
                inString = !inString;
                prepared.append(character);
                continue;
            }
            if (inString || character != ':') {
                prepared.append(character);
                continue;
            }
            if (index + 1 < sql.length() && sql.charAt(index + 1) == ':') {
                prepared.append("::");
                index++;
                continue;
            }
            int end = index + 1;
            while (end < sql.length() && isNameCharacter(sql.charAt(end))) {
                end++;
            }
            if (end == index + 1) {
                prepared.append(character);
                continue;
            }
            bindings.add(sql.substring(index + 1, end));
            prepared.append('?');
            index = end - 1;
        }
        return new Scan(prepared.toString(), List.copyOf(bindings));
    }

    private static boolean isNameCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }
}
