# ADR-030: The observation catalog declares a version it is read by

Status: Accepted

## Context

ADR-022 froze `faultora.dev/v1` for scenarios and recorded that the
observation catalog's `apiVersion` was validated by nothing: a catalog
claiming `faultora.dev/v99` was imported, and one declaring no version at all
was imported too. The catalog is the document that decides which SQL a run may
execute against a database, so accepting a version this release cannot read is
accepting a contract nobody has written.

## Decision

The observation importer reads the version before it builds a single target.

| Declared `apiVersion` | Result |
|---|---|
| `faultora.dev/v1` | Imported. |
| `faultora.dev/v1alpha1` | Imported, with a warning that names the release that stops reading it (2.0) and the command that moves it. |
| absent or null | Refused: `MISSING_API_VERSION`. |
| anything else, including a non-string | Refused: `UNSUPPORTED_API_VERSION`, naming the versions this release reads. |

A refusal happens during catalog assembly, before targets exist, so no database
is contacted on behalf of a catalog this release cannot hold to a contract. The
echoed version is truncated, because the value comes from a file and a
diagnostic is not the place to repeat it at length.

The deprecation follows ADR-022 for the same reason: refusing `v1alpha1` on a
release meant to be stable would break every catalog written against the
preview. A missing version is not a deprecation — it is a document that makes no
claim — and guessing one would be the drift a version exists to prevent.

## Why the versions are stated in the importer

ADR-022 names `ApiVersions` as the one place that knows the scenario's versions.
It lives in `faultora-spec`, and importers depend on `spi`, not on the scenario
parser (ARCHITECTURE.md §12.1). Reaching across that boundary for two constants
would couple an importer to the scenario model. More importantly, the two
documents are versioned by separate decisions: the scenario surface is frozen
and snapshot-tested, the catalog surface is not yet. They share a namespace and
today the same two values; when either moves, it moves on its own ADR.

## Rejected alternatives

- **Accept a missing version as the current one.** Silent, and wrong the first
  time the catalog format changes: a document written for one version would be
  read as another without anyone having said so.
- **Warn on an unknown version and import anyway.** A warning on the document
  that grants database access is a line in a log nobody reads, on the one run
  where it mattered.
- **Depend on `faultora-spec` for `ApiVersions`.** Covered above.
- **Freeze the catalog surface with a snapshot now.** ADR-022 ties that to the
  same treatment for the AsyncAPI and observation imports; it is a separate
  decision, not a side effect of validating the version.

## Limits

Importer warnings, this deprecation notice among them, are returned in the
`ImportResult` but are not yet surfaced by catalog assembly in the CLI, runner
or MCP; that applies to every importer warning and is a separate change.
