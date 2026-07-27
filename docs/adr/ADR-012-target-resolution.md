# ADR-012: Catalog-based target resolution with operator redirects

## Status

Accepted

## Context

Until 0.4.0 the engine ignored the catalog when deciding where to send a
request: every operation went to the single `baseUrl` in the connector config,
built into a synthetic `TargetDefinition` on the spot. The code said as much —
"in M1, we construct a basic target from the context config".

With one target that is invisible. It stops being invisible at the next
milestones: M3 verifies an HTTP-to-Kafka-to-database workflow, where a scenario
addresses several systems at once, and fault steps already name a
`targetScope`, which only means something if targets have distinct identities.
Building the target from configuration also discarded everything the imported
description knew about it — protocols, authentication schemes, metadata —
which is precisely what a connector needs in order to authenticate.

## Decision

- **The catalog is the source of a target's identity.** `TargetResolver` looks
  the operation's `TargetId` up in `ApiCatalog.targets()` and passes the
  declared definition — name, protocols, auth schemes, metadata — to the
  connector.
- **The operator redirects where a target lives, and nothing else.** A plain
  `--target <url>` rebinds every declared target; a repeatable
  `--target <id>=<url>` rebinds one. A redirect replaces the base URL and
  preserves the rest of the definition.
- **An unresolvable target is an error.** When the catalog does not declare the
  target and no redirect names it, the operation fails with
  `TARGET_NOT_FOUND` rather than being sent to a fabricated endpoint.
- A scenario cannot introduce a target or a URL. Destinations come from the
  imported description and the operator's options, which is what keeps the
  destination policy meaningful.
- **The base URL written in a description is never contacted on its own.** The
  CLI always supplies a global binding, defaulting to `http://localhost:8080`,
  so a run reaches the host a specification declares only when the operator
  passes it. A description committed to a repository therefore cannot direct
  traffic at the environment it happens to document.

## Rejected alternatives

- **Base URLs in the scenario document**: a scenario is committed to a
  repository and shared across environments; embedding endpoints in it invites
  a staging document to point at production, and it would let a scenario widen
  what the run may reach.
- **Environment variables per target** (`FAULTORA_TARGET_LEDGER`): invisible in
  the command that produced a run, and impossible to record accurately in the
  run manifest.
- **Keeping the single-URL shortcut until M3**: the shortcut is not a missing
  feature but wrong behaviour — it silently discards catalog data the
  connector needs and cannot express `targetScope` at all.

## Consequences

- Single-target runs behave exactly as before: one `--target` rebinds the one
  declared target, and a run without `--target` still reaches only
  `http://localhost:8080`.
- Multi-target catalogs are now executable without further engine changes;
  what M3 still needs is connectors for the other protocols, not target
  plumbing.
- Authentication schemes declared per target reach the connector, which is a
  precondition for supporting more than one credential in a run.
- The run's effective target bindings are visible in the command line and in
  the connector config recorded for the run.
