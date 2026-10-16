# Result and journal contract, version 1

This contract freezes the current JSON representation independently of scenario
YAML and runner transport versions. Existing files gain no envelope or version
field. A future incompatible format must have an explicit versioned entry point;
it cannot silently replace these files.

`events.ndjson` contains one event per line. The JSON report is an array of the
same events in journal order, not a serialized engine `RunResult`. The in-memory
result has its own frozen projection for consumers that serialize it.

Every event requires `eventType` (nonempty string), `timestamp` (epoch milliseconds,
integer), and `runId` (nonempty string). Every field of each known event record is
present, including explicit null values. Event-specific fields and their types
are enumerated by the checked-in `output/v1/events.json` fixture. The corresponding
`results.json` freezes run and node result fields, enum values, normalized errors,
and assertion results for success, failure, and cancellation. Fixture timestamps,
IDs, digests, and durations are fixed synthetic values.

Closed enum values are frozen separately in `enums.json`. SKIPPED remains a
node outcome; its result fixture guards the existing serialized enum value and
does not authorize emitting it as an overall run status. Object key order is not
part of the schema; journal map keys are sorted for reproducible bytes.

Readers must ignore unknown additive object fields, including nested error fields,
and skip unknown event types while preserving known-event order. A missing field
of a known event, a changed field type, an unknown enum value, or an invalid
envelope is an error. `RunEventReader.readLine` implements this journal policy.
Skipping an unknown event does not mean the run passed: consumers must observe
the known terminal events and treat an incomplete journal as incomplete.

Field removal, renaming, type changes, nullability tightening, and changes in
meaning require an explicit compatibility decision and migration. New event types
and optional fields are additive for conforming readers. Golden serialization
tests intentionally require review even for additive producer changes. They cover
every permitted event subtype and every result status, compare actual NDJSON and
JSON report serialization, and prove reader compatibility separately. Updating a
fixture to silence a failure without documenting compatibility is not permitted.

The journal records coordinates and digests, never payloads or credentials.
`MESSAGE_PUBLISHED.key` remains present and null in exported events. Error and
assertion diagnostics must pass the existing evidence export policy before they
reach these contracts. Serialization is not a redaction boundary.

Rejected alternatives: adding a version field to every existing event would
break strict readers immediately; freezing the YAML version would not protect
output consumers; snapshotting wall-clock E2E output would create noisy fixtures.
This boundary freezes structural serialization with deterministic fixtures and
leaves execution semantics covered by engine and E2E tests.
