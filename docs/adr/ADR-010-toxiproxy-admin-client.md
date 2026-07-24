# ADR-010: Toxiproxy network faults through a minimal admin client

## Status

Accepted

## Context

ADR-009 shipped in-process faults first and named Toxiproxy as the intended
mechanism for genuine network faults (roadmap M2-03). Adding it raised three
questions: which client to use, how scenarios address a proxy, and what the
rollback guarantee honestly is when the fault lives outside the CLI process.

## Decision

- Implement `faultora-faults-toxiproxy` against the **Toxiproxy admin HTTP
  API directly** with the JDK `java.net.http.HttpClient` and Jackson, which
  are already in the build. No third-party Toxiproxy SDK enters the supply
  chain.
- Expose fault types `network-latency`, `network-timeout`, `network-reset`,
  and `network-bandwidth` over the **same `FaultProvider` SPI** used by the
  in-process provider; the engine's `FaultSession` (watchdog, exactly-once
  rollback, end-of-run sweep) applies unchanged.
- The fault step's **`targetScope` names the Toxiproxy proxy**; `*` is
  rejected because a network fault must be tied to a concrete traffic path.
- The provider is enabled only by the operator-supplied
  `--toxiproxy-url` CLI option, which also adds the `network-*` types to the
  execution policy's fault allowlist. Scenarios cannot introduce an admin
  endpoint.
- Toxics get unique `faultora-` prefixed names, and rollback treats a 404 as
  already-removed, so rollback is idempotent and leaked toxics are
  identifiable.

## Rejected alternatives

- **toxiproxy-java client dependency**: small API surface does not justify a
  new supply-chain entry in a project that ships SBOMs and dependency gates.
- **Testcontainers-based integration tests in the default build**: would make
  `./mvnw verify` require Docker. Unit tests run against a stub admin server;
  real-instance testing remains possible manually via `--toxiproxy-url`.
- **Server-side fault TTL**: Toxiproxy has no toxic expiry, so a hard expiry
  cannot be delegated to it.

## Consequences

- Network faults exercise the real network path (proxy required on that
  path); the report's fault-window attribution still uses the CLI's clock, so
  cross-process timing is approximate by design.
- The rollback guarantee holds while the CLI process lives. If the JVM is
  killed, a toxic can outlive the run; the documented remediation is deleting
  `faultora-*` toxics with `toxiproxy-cli`. A supervising runner with lease
  semantics (M4) is the structural fix.
