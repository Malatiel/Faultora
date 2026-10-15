# ADR-026: Evidence availability and structural exports

## Status

Accepted

## Context

Removing content from capture does not prove that content was absent at the
target. A redaction marker is not the value it replaced. Assertions that treat
either as observed data can produce a false pass or an unjustified failure.
Conversely, permission to compare a value in memory is not permission to write
it into an assertion message, error, journal, or report.

## Decision

- `EvidencePolicy` continues to authorize bounded evidence held in memory for
  assertions and step outputs. Local and runner defaults and runner-side
  narrowing remain unchanged. No new scenario setting can expand capture.
- Availability travels beside captured evidence. HTTP evidence reports whether
  its whole body is complete and whether a requested header was withheld.
  Tables identify redacted columns. Messages report payload completeness and
  header capture/denial independently. Existing SPI constructors and default
  methods remain available for producers without provenance.
- JSON and schema checks over withheld, redacted, or truncated documents are
  indeterminate. This applies to the entire document: JMESPath filters,
  functions, and comparisons can derive results from a hidden field even when
  the selected result contains no marker. Tabular checks can still use other
  columns and counts. Event checks cannot treat withheld payloads or headers
  as missing fields; sequences conservatively refuse partially readable input.
- `outputAs` omits incomplete bodies, incomplete message payloads, and redacted
  cells. A later expression cannot turn the marker into an ordinary expected
  value. An actual literal `***` with complete provenance remains ordinary data.
- Export is a separate, fixed structural policy, implemented by `EvidenceExport`.
  All provider assertion results are projected to their outcome and a fixed
  description before being stored in node results, journal events, poll details,
  or terminal errors. Provider details and arbitrary messages are withheld for
  passing, failing, and indeterminate checks alike.
- Connector errors cross the same boundary before retry events are emitted.
  Export keeps category, retryability, known built-in codes (or `CONNECTOR_ERROR`),
  integral `vendorCode`, `maxBytes`, `statusCode`, a five-character SQLState,
  and boolean `connectionCloseFailed`. Other metadata, URLs, exception text,
  causes, and nested values are withheld. Unexpected provider exceptions use
  fixed node/group diagnostics. Raw target URLs are not printed by `test`.
- Published message keys stay available to authorized in-memory assertions but
  are null in journal events. Body/row content remains represented by counts,
  sizes, and digests. Reports derive from these already minimized journal
  events, and local and dispatched execution use the same engine boundary.
- Declared run/node/operation identifiers, assertion types, channel names,
  input parameter locations, fault types/scopes, and opaque fault handles remain public topology metadata.
  They must not be used to carry secrets. This policy does not sanitize an
  arbitrary hand-written journal, input specification, third-party logger, or
  an approved in-process extension acting outside its SPI contract.

## Alternatives

- Searching messages for known sensitive values misses transformations,
  fragments, and values from sources the engine never captured. Structural
  projection excludes content regardless of its representation.
- Keeping detailed messages when body capture is enabled confuses permission
  to compute with permission to export. There is no opt-in for raw diagnostics
  in this change; a future wider export policy needs its own authorization.
- Recognizing the string `***` as redacted misclassifies legitimate values and
  misses computed derivatives. Explicit provenance keeps these cases distinct.
- Matching simple redaction prefixes against JMESPath expressions cannot prove
  that a filter or function did not read a hidden field. Whole-document refusal
  is conservative and requires no second expression dependency analyzer.

## Consequences

Assertion outcomes retain their semantics on complete evidence. Journals and
reports provide less comparison detail by design; they identify the failing
node, assertion type, outcome, and structural error rather than its values.
An indeterminate assertion still fails the run. A scenario relying on redacted
JSON fields, including unrelated fields of the same document, must revise its
approved capture policy or use a different observation. Capture retention is
in-memory session lifetime; encryption, durable evidence stores, diagnostic
bundles, and sandboxing are separate requirements.
