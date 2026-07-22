# ADR-005: Run event schema

## Status

Accepted

## Context

Faultora needs a format for recording run lifecycle events that is append-only,
machine-readable, and supports streaming writes during execution. Events must
be deterministic and replayable.

## Decision

- Use **NDJSON (newline-delimited JSON)** as the run event format.
- Each line is a self-contained JSON object with a `eventType` discriminator
  and `timestamp`.
- Events are append-only: new events are written to the end of the file.
- The event schema is defined as a sealed interface (`RunEvent`) in
  `faultora-model` with `@JsonTypeInfo`/`@JsonSubTypes` for polymorphic
  serialization.
- Large bodies are stored as evidence blobs referenced by digest, not inline
  in events.

## Rejected alternatives

- **Protocol Buffers**: Requires schema compilation and adds build complexity.
  JSON is more inspectable for debugging.
- **Avro**: Similar to Protobuf. Overkill for a local event log.
- **SQLite**: Adds a database dependency. NDJSON is simpler and more portable.
- **JSON Array**: Not appendable without rewriting the file. NDJSON supports
  streaming writes.

## Consequences

- Events can be written incrementally during execution without buffering.
- The NDJSON format is human-readable and grep-friendly.
- Large evidence is referenced by digest, keeping events small.
- Readers must handle unknown event types gracefully (additive evolution).
- The sealed interface in Java ensures compile-time exhaustiveness for known
  event types.
