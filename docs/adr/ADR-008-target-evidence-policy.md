# ADR-008: Target and evidence policy model

## Status

Accepted

## Context

Faultora must constrain what targets a run can access, what operations it can
invoke, and what evidence it can capture. Policies must be explicit, auditable,
and enforceable by the worker independently of the controller.

## Decision

- `TargetPolicy` constrains: allowed targets, allowed operation safety
  classes, max requests, max concurrency, max duration, max payload size,
  allowed fault types, and allowed environments.
- `EvidencePolicy` constrains: body capture toggle, header capture toggle,
  header denylist, max body size, max rows, redaction paths, content type
  allowlist, and retention class.
- `ExtensionPolicy` constrains: allowed extensions, process isolation
  requirement, memory limits, network destinations, and secret capabilities.
- Policies are part of the compiled plan so distributed workers can enforce
  them independently.
- Policy failure is terminal and cannot be downgraded by a scenario.

## Rejected alternatives

- **Per-field annotations**: Too granular. A single policy record is easier
  to audit and serialize.
- **Policy as configuration file**: Policies must be part of the compiled
  plan, not external configuration that could be modified after compilation.
- **Implicit defaults**: Violates the "explicit beats convenient" principle.
  All policy fields must be set explicitly.

## Consequences

- Every run has a complete, auditable policy snapshot.
- Workers enforce policy locally without controller communication.
- A scenario cannot expand its policy through configuration merging.
- Policy violations produce `RunAuditEvent` entries for security review.
- The `EvidencePolicy.MINIMAL` constant provides a safe default for
  metadata-only capture.
