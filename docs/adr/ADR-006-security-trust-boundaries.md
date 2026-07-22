# ADR-006: Security trust boundaries

## Status

Accepted

## Context

Faultora operates in environments where API descriptions, credentials, test
traffic, and evidence are sensitive. The architecture must define clear trust
boundaries between the user, the engine, extensions, and target systems.

## Decision

- **Local CLI mode**: trust boundary is the OS process and filesystem. No
  network listener by default.
- **Engine ↔ Extensions**: extensions receive only capability-scoped inputs.
  The engine enforces policy independently of extension behavior.
- **Connector ↔ Target**: connections go only to declared targets. Redirects,
  DNS, and proxy destinations are validated against policy.
- **Fault provider ↔ Target**: faults have hard expiry and mandatory rollback.
  Loss of controller connectivity cannot extend a fault.
- **Evidence pipeline**: evidence is classified, minimized, redacted, and
  bounded before persistence. Secrets never enter evidence.
- **Extension isolation**: built-in extensions share the classloader.
  Future remote extensions run in separate processes with versioned RPC.

## Rejected alternatives

- **Container-based isolation for M0/M1**: Overkill for built-in extensions.
  Process isolation is deferred to M4+.
- **Mandatory mTLS for local mode**: Local mode has no network listener.
  mTLS is required only for distributed mode (M4+).

## Consequences

- Each boundary crossing requires authentication, capability validation,
  and policy enforcement.
- The engine trusts its own model and SPI interfaces but not extension
  implementations.
- Target connections are deny-by-default with explicit allowlists.
- Evidence handling follows the observe → classify → minimize → redact →
  bound → encrypt → retain/delete pipeline.
