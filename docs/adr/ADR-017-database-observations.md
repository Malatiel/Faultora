# ADR-017: Database observations come from the operator, not the scenario

## Status

Accepted

## Context

M3-03 asks for parameterized read-only database observations. It does not say
who writes the queries, and that is the decision — everything else follows from
it.

A database observation is the first thing Faultora runs that it did not learn
from a published contract. An OpenAPI document says what the API offers; an
AsyncAPI document says what the channels carry. There is no equivalent for
"which rows a test may read", so somebody has to write the SQL.

The obvious place is the step that makes the observation. That place is wrong.

## Decision

- **Queries live in an operator's document, imported into the catalog.** SEC-07
  says a scenario carries no arbitrary code, and a `SELECT` in a scenario is
  arbitrary code with a keyword in front of it: it can read any table the
  credentials allow, and nobody reviewing a deployment has a list of what a run
  may see. The observation catalog is that list, it lives beside the
  deployment, and a scenario names an entry in it.
- **It is imported like any other description.** The same `SourceImporter`
  contract, the same `--observations` beside `--openapi` and `--asyncapi`, the
  same union into one catalog with duplicate names refused. Its `servers`
  become targets, so `--target ledger=jdbc:…` redirects a database exactly as
  it redirects an API to staging — a document committed to a repository never
  names the database a run actually reads.
- **Read-only is enforced three times, and only the third is a guarantee.** The
  connector accepts a single reading statement; the connection is set
  read-only; and the documentation asks for read-only credentials. The first
  two are code, and code is one defect away from being wrong. A grant is not.
  An operator who gives this connector a writing account has removed the only
  protection that does not depend on this project being correct, and the
  connector's own javadoc says so.
- **A statement is read whole, not by its first word.** The first version of
  this check read the opening keyword, and the opening keyword is not the
  statement: PostgreSQL runs `WITH x AS (DELETE FROM ledger RETURNING *) SELECT
  * FROM x`, which begins `WITH` and deletes rows, and `SELECT … INTO` creates
  a table while beginning `SELECT`. So every bare word — outside string
  literals, quoted identifiers and comments — is checked against a list of
  words that write, and one of them refuses the observation before a connection
  opens.
- **The statement check is strict rather than clever.** A parser that
  understood every dialect could permit more and would be wrong somewhere. A
  list of words that may not appear is a rule an operator can hold in their
  head, and a refused query can always be rewritten. The `;` rule belongs to
  the same choice: a semicolon with anything after it is what would put a
  second statement beyond the check the first passed.
- **A URL whose host cannot be found is refused, not allowed.** Not every
  driver writes `//host`; Oracle's thin driver writes
  `jdbc:oracle:thin:@host:1521:SID`. Reading a missing `//` as "in-process, so
  nothing to classify" made the destination policy silently optional for those
  drivers. A subprotocol that reaches no network is now named explicitly, and
  everything else must show its host to be checked.
- **The evidence policy applies to rows, through the shared capture.** Values
  are content and counts are not: a policy capturing no bodies keeps the row
  count and drops the values, and `TableEvidence.valuesWithheld` makes an
  assertion that reads one indeterminate rather than wrong. A redact path whose
  leading segment names a column replaces that cell whole — matching further
  into a cell would mean parsing it, and a cell that cannot be parsed could not
  then be redacted.
- **Values are bound, never interpolated.** Named `:parameters` become
  positional markers in one pass that also produces the binding order, so the
  two cannot disagree. A `::` cast is not a parameter and neither is a colon
  inside a literal; binding into either would put a value where the author
  wrote something else.
- **Rows are bounded at the driver.** `EvidencePolicy.maxRows` — declared since
  0.1 and enforced nowhere until now — becomes `setMaxRows` with a matching
  fetch size, so rows that are not kept are not fetched. This tool causes load
  on a system somebody else operates, and fetching ten thousand to report a
  hundred is load they pay for. The connection does not autocommit, which reads
  oddly for one that never writes and is what makes the sentence true:
  PostgreSQL opens the server-side cursor a fetch size depends on only inside a
  transaction, and in autocommit the driver materializes the whole result set.
  The connection belongs to one observation and is closed with it. What is
  proved is that the driver's documented precondition now holds; no test
  measures a server-side portal, and the gate's own reads are two rows.
- **A truncated result says so.** One row past the limit is read to learn that
  the result was cut, and `TableEvidence.truncated` carries it. An assertion
  that counts rows against a truncated result is counting the limit, so the
  counting assertions refuse it rather than answering.
- **A connection per observation.** A JDBC connection belongs to one thread and
  the engine prepares and releases around every invocation — the same shape the
  Kafka consumer has, arrived at the same way.
- **`ObservationNode` is not built, and the promise is removed.** The
  architecture document has listed it since M3-03 was written. A database read
  turns out to be an operation with inputs and evidence, needing no lifecycle
  an operation node lacks — exactly as events needed no node type of their own.
  A node kind promised for a milestone and absent after it ships is drift; the
  promise goes rather than the code gaining a type it does not need.
- **One driver ships in the executable.** A shaded jar cannot take a driver
  from `-cp`, so shipping none would make observations unusable from the
  released artifact, and shipping every driver would be a licence and size
  problem. PostgreSQL ships; another database means building the CLI with its
  driver, and the documentation says so rather than leaving it to be discovered.

## Rejected alternatives

- **SQL in the scenario step.** Reviewed above: it defeats SEC-07 and leaves an
  operator with no list of what a run may read.
- **A query allowlist by table name** rather than by whole statement. It sounds
  narrower and is broader: any statement touching an allowed table passes,
  including one that joins it to something else.
- **Trusting the catalog's `READ_ONLY` classification.** The catalog is the
  document with the SQL in it. A file cannot classify itself honestly.
- **Refusing `WITH` outright** rather than scanning for words that write. It
  would close the same hole and would cost every ordinary common table
  expression, which is how readable analytical SQL is written. Scanning refuses
  the same statements and keeps the ones nobody was worried about.
- **Replacing the driver's message.** It is the only thing that names the
  column that does not exist, and several drivers quote the offending value
  inside it. The message is bounded rather than dropped, and `docs/SECURITY.md`
  states the exposure — a connector that silently swallowed it would trade a
  stated risk for an unusable diagnostic.
- **`DriverManager.setLoginTimeout` for the connect timeout.** It is portable
  and it is process-wide: it would change how every other driver in the JVM
  connects, including one a host application owns. The timeout travels as a
  connection property instead, to the drivers documented to take it.
- **A connection pool.** It would help a scenario making many observations and
  would have to be closed on connector close rather than on release; the
  correctness question it raises is the one that bit the Kafka consumer, and
  the performance it buys is not yet needed.

## Consequences

- An observation cannot be written without an operator's document. That is the
  point, and it is friction: a developer exploring a database has to add an
  entry rather than paste a query.
- Only PostgreSQL is reachable from the released jar. H2 covers the connector's
  own tests, and the dialect gap between them is real — an H2 test proves the
  bounds and refusals, never that a query runs.
- `EvidencePolicy.maxRows` now constrains something, so a policy that sets it
  low will truncate results and the counting assertions will say so rather than
  answer wrongly. That is a behaviour change for any policy that set it
  meaninglessly, which every policy did.
