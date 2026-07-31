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
 * Reading the first keyword is not enough, which is the mistake this class made
 * first. PostgreSQL allows a data-modifying common table expression —
 * {@code WITH x AS (DELETE FROM ledger RETURNING *) SELECT * FROM x} — which
 * begins with {@code WITH} and deletes rows, and {@code SELECT … INTO} creates
 * a table while beginning with {@code SELECT}. So the whole statement is
 * scanned, outside string literals and quoted identifiers and comments, and any
 * word that writes refuses it.
 * <p>
 * It is deliberately strict rather than clever. A parser that understood every
 * dialect could permit more and would be wrong somewhere; a list of words that
 * may not appear is a rule an operator can hold in their head, and a refused
 * query can always be rewritten. The connection is set read-only as well, and
 * the documentation asks for read-only credentials on top — because a check is
 * one defect away from being wrong, and a grant is not.
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
     * Words that write from inside a statement that began by reading.
     * <p>
     * This list is short on purpose, and every omission is deliberate. A word
     * that can only <em>begin</em> a statement — {@code call}, {@code vacuum},
     * {@code set}, {@code replace} in {@code REPLACE INTO} — is already refused
     * twice over: it is not in {@link #READING}, and a second statement needs a
     * {@code ;} that is refused on its own. Listing it here would buy nothing
     * and would cost real queries, because these are words that also occur as
     * identifiers: {@code comment} is an ordinary column name and
     * {@code replace} an ordinary function. A check that refuses
     * {@code SELECT id, comment FROM entries} is not strict, it is broken.
     * <p>
     * What remains is what can write from within a reading statement:
     * {@code into} because {@code SELECT … INTO} creates a table, the four
     * data-modifying words a common table expression can carry, and the
     * schema-changing words that are reserved everywhere and so cannot be
     * mistaken for a column. {@code returning} is absent because it only shapes
     * what a write returns — the write itself is already named here.
     */
    private static final Set<String> WRITING = Set.of(
            "insert", "update", "delete", "merge", "into",
            "create", "alter", "drop", "truncate", "grant", "revoke");

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
        Scan scan = scan(sql);

        String opening = scan.words().isEmpty() ? "" : scan.words().get(0);
        if (!READING.contains(opening)) {
            throw new IllegalArgumentException(
                    "An observation runs a single reading statement, and this one begins '"
                            + opening + "'. Faultora observes a database; it never writes to "
                            + "one, and the credentials it connects with should not let it");
        }
        if (scan.endsAStatementEarly()) {
            throw new IllegalArgumentException(
                    "An observation runs one statement, and this one contains a ';' that "
                            + "ends it early. Two statements in one observation would put "
                            + "the second beyond the check the first passed");
        }
        String writing = scan.words().stream().filter(WRITING::contains).findFirst().orElse(null);
        if (writing != null) {
            throw new IllegalArgumentException(
                    "An observation contains '" + writing + "', which writes. A statement "
                            + "that begins by reading can still write — a common table "
                            + "expression may delete, and SELECT … INTO creates a table — so "
                            + "the whole statement is read, not just its first word");
        }
        return new ReadOnlyStatement(sql, scan.prepared(), scan.bindings());
    }

    /** The distinct parameter names this statement binds. */
    List<String> parameters() {
        return List.copyOf(new LinkedHashSet<>(bindingOrder));
    }

    /**
     * What one reading of the statement found.
     *
     * @param prepared            the statement with positional markers
     * @param bindings            parameter names in binding order
     * @param words               every bare word, lowercased, outside literals,
     *                            quoted identifiers, and comments
     * @param endsAStatementEarly whether a {@code ;} has anything after it
     */
    private record Scan(
            String prepared,
            List<String> bindings,
            List<String> words,
            boolean endsAStatementEarly
    ) {}

    /**
     * One pass over the statement, producing everything decided about it.
     * <p>
     * The answers come from one reading rather than from several that could
     * disagree about where a literal starts. Comments are skipped rather than
     * read: a trailing {@code -- done} is ordinary, and refusing it would make
     * an operator strip comments out of a document meant to be read by people.
     */
    private static Scan scan(String sql) {
        StringBuilder prepared = new StringBuilder(sql.length());
        List<String> bindings = new ArrayList<>();
        List<String> words = new ArrayList<>();
        boolean statementEnded = false;
        boolean somethingAfterTheEnd = false;

        int index = 0;
        while (index < sql.length()) {
            char character = sql.charAt(index);

            if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                int end = sql.indexOf('\n', index);
                end = end < 0 ? sql.length() : end;
                prepared.append(sql, index, end);
                index = end;
                continue;
            }
            if (character == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                end = end < 0 ? sql.length() : end + 2;
                prepared.append(sql, index, end);
                index = end;
                continue;
            }
            if (character == '\'' || character == '"') {
                int end = closingQuote(sql, index, character);
                prepared.append(sql, index, end);
                if (statementEnded) {
                    somethingAfterTheEnd = true;
                }
                index = end;
                continue;
            }
            if (character == ':' && index + 1 < sql.length() && sql.charAt(index + 1) == ':') {
                prepared.append("::");
                index += 2;
                continue;
            }
            if (character == ':' && index + 1 < sql.length()
                    && isNameCharacter(sql.charAt(index + 1))) {
                int end = index + 1;
                while (end < sql.length() && isNameCharacter(sql.charAt(end))) {
                    end++;
                }
                bindings.add(sql.substring(index + 1, end));
                prepared.append('?');
                index = end;
                continue;
            }
            if (Character.isLetter(character) || character == '_') {
                int end = index;
                while (end < sql.length() && isNameCharacter(sql.charAt(end))) {
                    end++;
                }
                words.add(sql.substring(index, end).toLowerCase(Locale.ROOT));
                prepared.append(sql, index, end);
                if (statementEnded) {
                    somethingAfterTheEnd = true;
                }
                index = end;
                continue;
            }
            if (character == ';') {
                statementEnded = true;
            } else if (statementEnded && !Character.isWhitespace(character)) {
                somethingAfterTheEnd = true;
            }
            prepared.append(character);
            index++;
        }
        return new Scan(
                prepared.toString(), List.copyOf(bindings),
                List.copyOf(words), somethingAfterTheEnd);
    }

    /** The index just past a quoted run, or the end when it is unterminated. */
    private static int closingQuote(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                // A doubled quote is an escaped one and does not close the run.
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    private static boolean isNameCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }
}
