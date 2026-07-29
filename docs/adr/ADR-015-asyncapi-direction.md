# ADR-015: The direction of an imported AsyncAPI operation

## Status

Accepted

## Context

AsyncAPI 3.0 states an operation's `action` from the point of view of the
application the document describes. `send` means that application sends;
`receive` means it receives. This is a deliberate change from 2.x, where
`publish` and `subscribe` were stated from the point of view of whoever was
reading the document — the ambiguity 3.0 exists to remove.

Faultora is not the application. It is the other side. So the direction a
connector needs is the opposite of the one the document states, and there is
exactly one place to perform the inversion.

Getting this backwards would be quiet. Every scenario would publish where it
should observe, every test would agree with the code, and the first sign of
trouble would be a run that sees no events and a target that received commands
it never expected — from a tool whose entire purpose is to be trusted about
what it sent.

## Decision

- **The importer inverts, and nothing else does.** An application that
  `receive`s is a channel the run *publishes* to; one that `send`s is a channel
  the run *consumes* from. By the time an operation reaches the catalog its
  `action` is `publish` or `consume` in the run's own terms, and connectors
  never see AsyncAPI's vocabulary.
- **Safety follows the inverted direction.** What the run publishes changes the
  application's state and is `MUTATING`; what it consumes is `READ_ONLY`. This
  is the reason the inversion has to be right rather than merely consistent: a
  write classified as a read passes the execution policy without being asked
  for, and the operator's `--allow-*` decisions stop meaning anything.
- **A published payload becomes the operation's request schema.** Generation
  from schemas already reads it there, so a scenario can generate a command
  from the contract with no new mechanism. A consumed payload becomes an
  outcome instead.
- **AsyncAPI 2.x is refused by name.** Its direction is stated from the
  opposite point of view, so importing it under 3.0's rule would reverse every
  operation in the document. Refusing with a diagnostic that says why is the
  only safe reading of a 2.x file. Supporting it is a separate work item, not
  a silent best effort.
- **A server of an unsupported protocol is reported, not fatal.** A description
  usually covers more than the part under test; losing a whole catalog over one
  MQTT server would be the wrong trade. The operations bound to it are skipped
  and named.

## Rejected alternatives

- **Inverting in the connector.** It would put the knowledge of a document
  format inside a protocol client, and a second importer for the same protocol
  would have to agree with it by convention.
- **Carrying AsyncAPI's `action` into the catalog unchanged**, letting each
  consumer decide. Every consumer would then have to know the rule, and the
  safety classification — which is decided at import — would have nothing to
  base itself on.
- **Treating both directions as `MUTATING` to be safe.** It would make every
  observation require `--allow-destructive`-style permission for watching a
  channel, and an operator who grants that broadly is worse protected than one
  who grants it narrowly.

## Consequences

- A document written from the *client's* point of view — a mistake 3.0 makes
  easy to avoid but not impossible — imports backwards, and the scenario will
  publish to a channel it meant to watch. The summary of each operation is
  carried into the catalog so this is visible when reading the imported
  operations.
- An operation whose channel declares several messages uses the first one. The
  catalog carries one payload schema per operation, and choosing silently by
  document order is the alternative; this is named in a warning instead.
- Correlation locations are carried verbatim in AsyncAPI's runtime-expression
  form. Nothing evaluates them yet: they document where a correlation value
  lives, and a scenario names the same location itself in its `match` clause
  and its assertions.
