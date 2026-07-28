# Release plan

Status: proposed  
Companion to the [delivery roadmap](ROADMAP.md), which defines the milestones.
This page assigns them to versions and says what must be true before 1.0.

## 1. The rule that shapes everything

**1.0 freezes the scenario API and the result schema.** After it, the 1.x line
carries fixes only: defects, security updates, and documentation. Anything that
changes what a scenario may say, what a report contains, or what an extension
implements has to land before 1.0 — or wait for 2.0.

Two consequences worth stating plainly:

- a feature that only *adds* a step type still changes the scenario contract,
  so it belongs before the freeze;
- work the roadmap already defers past 1.0 (§16 of the roadmap) stays deferred,
  and becomes 2.0 material rather than 1.x.

## 2. Where 0.5.1 stands

| Milestone | State |
|---|---|
| M0 — Foundation | complete |
| M1 — HTTP vertical slice | complete except response-schema assertions |
| M2 — Reliability engine | complete except two reference scenarios |
| M3 — Event-driven and cross-component | not started |
| M4 — Private-network runner | not started |
| M5 — Distributed execution | not started |
| M6 — 1.0 hardening | not started |

Shipped and working: OpenAPI import, HTTP connector with SSRF and evidence
policy, scenario language with sequential, parallel, repeat, and eventually
blocks, retries, deadlines, in-process and Toxiproxy faults, request generation
from schemas, four report formats, and a reproducible run journal.

## 3. Releases

| Version | Milestone | User-visible outcome |
|---|---|---|
| 0.6 | M1/M2 debt | Everything the documentation already claims is true |
| 0.7 | M3 part 1 | A scenario can publish and observe Kafka events |
| 0.8 | M3 part 2 | One scenario proves a business invariant across HTTP, events, and a database |
| 0.9 | M4 | Runs execute inside a private network without inbound access |
| 0.10 | M5 | A controller shards one run across workers and returns one result |
| 1.0 | M6 | Frozen contracts, isolated extensions, signed artifacts, operational docs |

### 0.6 — Close what is already promised

No new capability. Every item here is something the docs, the roadmap, or an
ADR already states.

- **Response-schema assertions** (M1-06, owed since M1). `SchemaValidator` exists
  and is production-used; this is an assertion provider over it.
- **M2-05 reference scenarios still missing**: target restart during an
  operation, and cleanup after partial setup.
- **Generation gaps** recorded in the 0.5.0 review: examples declared on the
  media type rather than the schema, `nullable` and `additionalProperties`, and
  the seed divergence between the compile-time feasibility check and the run
  for `oneOf` branches.
- **`ExtensionPolicy.allowedExtensions` enforced at discovery** — ADR-004
  records that it is not, and SEC-08 stays unmet until it is.

Gate: no statement in `README.md`, `docs/`, or an accepted ADR describes
behaviour the code does not have.

### 0.7 — Events

- AsyncAPI importer: applications, channels, operations, messages, schemas,
  correlation IDs, Kafka bindings (M3-01).
- Kafka connector: publish, consume with bounded positions, isolated consumer
  groups, sanitized evidence, duplicate and delayed delivery (M3-02).
- Event assertions: eventually appears, count, uniqueness, correlation
  continuity, ordered and unordered sequences (M3-04, event half).
- Disposable Kafka in the test suite; the default build stays offline.

Gate: a scenario publishes a command, observes the resulting event, and proves
that duplicate delivery produces one business effect.

### 0.8 — Cross-component invariants

This is the release where the product's central claim becomes true.

- JDBC observation connector: parameterized read-only observations, statement
  and connection deadlines, bounded rows, tabular evidence, mutation refused by
  both connector policy and database credentials (M3-03).
- Tabular and compound assertions across HTTP, event, and database evidence
  (M3-04, remainder).
- Payment recovery reference system: transactional outbox, idempotent consumer,
  double-entry ledger, provider simulator with accepted-but-response-lost
  behaviour, reconciliation worker, and selectable known-broken variants
  (M3-05).

Gate: the M3 exit gate — at least one complete distributed business invariant
verified, with deterministic observation windows and cleanup.

### 0.9 — Private-network runner

- Runner protocol: versioned registration, outbound mutually authenticated
  connection, plan dispatch, progress, cancellation, artifact upload, leases
  (M4-01).
- Runner-side policy enforcement independent of the controller (M4-02).
- Packaging: container image, Compose and Kubernetes examples, health and
  readiness, deny-by-default network examples, offline bundle (M4-03).
- Remote-run qualification: the 0.7 and 0.8 suites executed through a runner,
  with controller connectivity interrupted mid-run (M4-04).

Gate: no inbound connection into the private network; disconnection cannot
extend a run or an active fault beyond policy.

### 0.10 — Distributed execution

- Controller API and metadata: projects, environments, policies, catalogs,
  scenarios, runs, tasks, artifacts; identity integration and audit events
  (M5-01).
- Scheduler and task leases (M5-02).
- Distributed worker sharing the local engine, with deterministic shard seeds
  (M5-03).
- Deterministic result aggregation across shards (M5-04).
- Admission control and quotas (M5-05).
- Distributed qualification: worker loss, controller restart, duplicate
  delivery, artifact-store interruption, cancellation while faults are active
  (M5-06).

Gate: the M5 exit gate — adding workers adds capacity without adding controller
traffic, no single worker failure loses a result or a cleanup obligation.

### 1.0 — Freeze and productization

- **`faultora.dev/v1` frozen**, with migration tooling and a compatibility
  matrix (M6-01). The internal step model becomes a sealed hierarchy here: it
  is the one moment when reshaping the parsed document costs nothing extra.
- Out-of-process extension protocol, manifests, capability validation, SDK,
  reference extension, process isolation and resource limits (M6-02).
- Supply chain: multi-architecture images, signed artifacts, SBOM, SLSA
  provenance, verifiable offline bundle, Helm chart, upgrade and rollback
  documentation (M6-03).
- Operational readiness: OpenTelemetry traces, metrics, structured logs, health
  contracts, backup and retention guides, diagnostic bundles, performance
  baselines (M6-04).
- Security qualification: final threat-model review, boundary tests, extension
  isolation tests, policy bypass tests, artifact sanitization, offline
  qualification (M6-05).
- Experience: guided initialization, examples by failure class, actionable
  diagnostics, searchable HTML timeline, CI examples, self-hosting and plugin
  authoring documentation (M6-06).

Gate: the roadmap's 1.0 exit gate, plus the compatibility tests that make
"fixes only" enforceable rather than aspirational.

## 4. After 1.0

The 1.x line takes defect fixes, security updates, dependency bumps, and
documentation. Everything in §16 of the roadmap — visual scenario builder,
hosted control plane, GraphQL and WebSocket connectors, gRPC reflection,
additional brokers, Kubernetes infrastructure faults, trace topology
assertions, scenario registry, organization policy packs, reliability trends,
IDE integration, property-based shrinking across distributed runs — opens the
2.0 line.

Shrinking deserves a note: 0.5.0 records the seed that reproduces a generated
failure, which is what makes a failure investigable. Reducing it to a minimal
counterexample is a separate capability and stays where the roadmap put it.

## 5. Scope risk, stated once

0.7 and 0.8 together are comparable in size to everything built between 0.2 and
0.5. 0.10 is comparable again on its own: a controller, a scheduler with
leases, a worker, aggregation, quotas, and a qualification suite are a
distributed system, not a feature.

An alternative worth weighing before starting 0.9: **cut 1.0 at the runner and
move distributed execution to 2.0.** The product's differentiator — a business
invariant verified across HTTP, events, and a database, under injected faults,
reproducibly — is complete at 0.8, and a private-network runner at 0.9 makes it
deployable where such systems actually live. Distributed sharding matters to
teams whose suites outgrow one machine, which is a problem adopters have after
the tool is in CI, not before.

Choosing that cut would renumber this plan: 0.9 becomes the last pre-freeze
release, 1.0 covers M6, and M5 opens 2.0. It does not change the order of the
work, only where the compatibility promise starts.
