# ADR-027: Shared run preparation and structural previews

## Status

Accepted

## Context

Catalog assembly and connector composition are shared, but local and dispatched
runs still parse and compile separately. The runner omitted structural scenario
validation. Adding another adapter would multiply these differences and make a
preview difficult to compare with execution.

## Decision

- `RunPreparation` in the existing runtime module owns parsing, structural
  validation, catalog assembly and compilation. CLI file reading, dispatch
  authentication, input binding, leases, cancellation and report delivery remain
  adapter responsibilities. `RunEnvironment` remains the execution boundary.
- A runner authenticates the dispatch, verifies document digests and narrows its
  policy before calling preparation. The shared service accepts an effective
  policy; it neither authenticates callers nor grants permissions. The verified
  dispatch catalog digest keeps its document-list meaning. Local runs retain
  the digest of their assembled catalog.
- Local catalog fingerprints use the UTF-8 preimage
  `faultora.catalog.v1\n` followed by compact JSON of the assembled catalog.
  Every JSON object's keys are sorted by their natural Java String order,
  including record fields and nested maps. Arrays retain their order; integer
  and decimal precision and decimal scale are preserved. This is a versioned
  Faultora representation, not an implementation of RFC 8785. A format change
  needs a new domain version. Signed dispatch document-list digests are not
  computed by this algorithm and keep their existing wire meaning.
- `RuntimeCapabilities` describes installed, admitted contract types. Advertised
  fault types are the intersection of deployment availability and target policy.
  Discovery uses the same artifact, origin, manifest and permission checks as
  execution, before plugin initialization. It does not claim plugin isolation.
  `CatalogDescription` exposes topology and static operation permission without
  endpoint URLs, schemas, examples or input values.
- `faultora test --preview` calls preparation and returns a structural JSON
  description before constructing fault providers or opening a run environment.
  It includes catalog identities, action/group identities, attempt multipliers,
  fault types/durations, budgets and located validation/compilation refusals.
  Diagnostic text is fixed; provider prose and document values are not exported.
  No credentials are resolved, journal is opened, report is written or fault is
  activated. A compiled preview uses exit 0; a scenario or compiler refusal uses
  exit 2. Existing option, file and input errors keep the CLI's usual handling.

## Consequences

Existing adapters use one preparation path without a new module or transport
dependency. A future MCP adapter must use this boundary after its own trust and
policy checks; no MCP transport is introduced here. Scenario YAML is unchanged.

Preview's `compiled` means static compilation succeeded. It does not establish
reachability, credential validity, provider-specific parameter validity or the
outcome of execution. Built-in preparation makes no target requests. Explicitly
admitted plugins still run in-process and can have their own side effects; this
API does not sandbox them.

A preview is descriptive, not an execution approval token. Binding immutable
prepared plans to scenario, catalog, input and policy digests needs a separate
prepared-run lifecycle. Complete machine diagnostics across CLI error paths and
separate ownership of run artifacts remain separate concerns.
