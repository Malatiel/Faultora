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

## Amendment (0.7.0)

Two event types were added for message channels: `MESSAGE_PUBLISHED` records
where a published message landed, and `MESSAGES_OBSERVED` records what an
observation window contained and how much of it the step's selector claimed.

Both follow the rule already stated above: the journal carries coordinates and
digests, never payloads. A message payload is governed by the evidence policy
and stays in memory, where the report decides whether to show it — a journal
that held payloads would be a file on disk holding whatever the policy said not
to keep.

Both are emitted from the node lifecycle by reading protocol-neutral message
evidence, so a second event protocol journals through the same path without the
engine learning anything about it.

## Consequences

- Events can be written incrementally during execution without buffering.
- The NDJSON format is human-readable and grep-friendly.
- Large evidence is referenced by digest, keeping events small.
- Readers must handle unknown event types gracefully (additive evolution).
- The sealed interface in Java ensures compile-time exhaustiveness for known
  event types.
