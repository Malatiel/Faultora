# Release plan

Status: proposed  
Companion to the [delivery roadmap](ROADMAP.md), which defines the milestones.
This page assigns them to versions and says what must be true before 1.0.

## 1. The rule that shapes everything

**1.0 freezes the scenario API and the result schema.** After it, the 1.x line
carries fixes only: defects, security updates, and documentation. Anything that
changes what a scenario may say, what a report contains, or what an extension
implements has to land before 1.0 — or wait for 2.0.

Three consequences worth stating plainly:

- a feature that only *adds* a step type still changes the scenario contract,
  so it belongs before the freeze;
- work the roadmap already defers past 1.0 (§16 of the roadmap) stays deferred,
  and becomes 2.0 material rather than 1.x;
- **2.0 is major because the deployment and trust model changes, not because
  the contract breaks.** A scenario written for 1.0 runs unchanged on 2.0.
  Distributed execution adds a controller, workers, and a scheduler around the
  same compiled plan — architecture principle 1 requires local and distributed
  runners to execute exactly that. A team that invests in a scenario suite
  before 1.0 keeps it afterwards.

## 2. Where 0.6.0 stands

| Milestone | State |
|---|---|
| M0 — Foundation | complete |
| M1 — HTTP vertical slice | complete |
| M2 — Reliability engine | complete |
| M3 — Event-driven and cross-component | not started |
| M4 — Private-network runner | not started |
| M5 — Distributed execution | not started |
| M6 — 1.0 hardening | not started |

Shipped and working: OpenAPI import, HTTP connector with SSRF and evidence
policy, scenario language with sequential, parallel, repeat, and eventually
blocks, retries, deadlines bounded by the execution policy, in-process and
Toxiproxy faults, request generation from schemas, assertions including the
response schema, four report formats, and a reproducible run journal.

## 3. Releases

| Version | Milestone | User-visible outcome |
|---|---|---|
| 0.6 | M1/M2 debt | Everything the documentation already claims is true |
| 0.7 | M3 part 1 | A scenario can publish and observe Kafka events |
| 0.8 | M3 part 2 | One scenario proves a business invariant across HTTP, events, and a database |
| 0.9 | M4 | Runs execute inside a private network without inbound access |
| **1.0** | M6 | Frozen contracts, isolated extensions, signed artifacts, operational docs |
| 2.0 | M5 | A controller shards one run across workers and returns one result |

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

**Every declared limit is enforced, or deleted.** A review found
`TargetPolicy.maxDurationMs` used nowhere: the CLI announces a five-minute
wall-clock budget, and a scenario writing `timeout: 1h` simply exceeds it. The
defect is one field; the problem is that nobody had checked which declared
limits are applied at all. Each field of `TargetPolicy`, `EvidencePolicy`, and
`ExtensionPolicy` is audited here and either enforced with a test that fails
when it is not, or removed. A limit that constrains nothing is worse than an
absent one, because it answers a reviewer's question falsely.

**Guarantees that do not currently hold.** Each contradicts an accepted ADR:

- numbers with exclusive bounds are generated at the excluded value, because
  only the integer path normalises them — ADR-013 promises a value the contract
  accepts, and the corpus test missed it by using integers;
- an exception thrown inside a parallel, repeat, or eventually group escapes
  the run loop, so cleanup does not run and no terminal event is journalled —
  the failure class fixed for the scenario deadline in 0.4.0, left half closed;
- a node whose dependency failed vanishes from the report instead of being
  recorded as skipped, so a reader cannot tell it from a step never written.

**Cancellation becomes a real path.** The cancellation flag exists and nothing
sets it: an interrupted run leaks whatever it injected. A signal handler that
sets it, waits for cleanup, and lets the run report its own termination is
worth more than the leak it prevents — the runner in 0.9 and the controller in
2.0 both cancel runs, and both need that path to work.

Gate: no statement in `README.md`, `docs/`, or an accepted ADR describes
behaviour the code does not have, **and every guarantee an ADR states has a
test that fails when it is violated**.

### 0.7 — Events

- AsyncAPI importer: applications, channels, operations, messages, schemas,
  correlation IDs, Kafka bindings (M3-01).
- Kafka connector: publish, consume with bounded positions, isolated consumer
  groups, sanitized evidence, duplicate and delayed delivery (M3-02).
- Event assertions: eventually appears, count, uniqueness, correlation
  continuity, ordered and unordered sequences (M3-04, event half).
- Disposable Kafka in the test suite; the default build stays offline.

Carried over from the 0.5 review, none of it blocking a stated guarantee:

- scenario-supplied strings that reach a management API are escaped and made
  unique. A Toxiproxy proxy name goes into a URL path unencoded, and toxic
  names come from a per-JVM counter, so a leaked name collides with the next
  run. The general question — what a scenario may put into a control plane —
  is one the controller inherits in 2.0;
- `FaultSession.start` registers a fault before scheduling its watchdog; a
  rejected schedule during a concurrent close leaves it injected. Unlikely,
  and precisely the guarantee the class exists for;
- the security document states that the destination allowlist skips
  private-range classification, which is deliberate and unreachable from the
  CLI today, and the scenario reference warns that retrying a non-idempotent
  operation can duplicate its effect — the hazard this tool exists to find,
  and worth naming where scenarios are written.

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

**The runner protocol must carry a version and negotiate it** — M4-01 already
lists "compatibility negotiation and graceful rejection", and here it becomes a
release-blocking criterion rather than a wish. The reason is timing: 1.0
freezes this protocol, while its only real consumer, the controller, arrives in
2.0 and will want shard descriptors, scheduling hints, and shard-level leases.
Negotiation is what lets 2.0 add a second protocol version beside the first
instead of breaking every runner already deployed. A mismatch must produce a
named refusal, never undefined behaviour.

### 1.0 — Freeze and productization

- **`faultora.dev/v1` frozen**, with migration tooling and a compatibility
  matrix (M6-01). The internal step model becomes a sealed hierarchy here: it
  is the one moment when reshaping the parsed document costs nothing extra.
- **Semantics nobody has decided are decided before they are frozen.** A freeze
  inherits whatever the code happens to do, including what was never a choice:
  a lone `{{expr}}` resolving to null while the same expression interpolated
  yields an empty string; any parenthesis routing an expression to JMESPath; an
  assertion named `jsonpath` that evaluates JMESPath and documents a `matches`
  check it does not implement; `equals` distinguishing 5 from 5.0. Each is
  small, and each becomes permanent on the day `v1` is declared.
- Out-of-process extension protocol, manifests, capability validation, SDK,
  reference extension, process isolation and resource limits (M6-02).
- Supply chain: multi-architecture images, signed artifacts, SBOM, SLSA
  provenance, verifiable offline bundle, Helm chart, upgrade and rollback
  documentation (M6-03).
- Operational readiness: OpenTelemetry traces, metrics, structured logs, health
  contracts, backup and retention guides, diagnostic bundles, performance
  baselines (M6-04). Evidence is held in memory for a whole run, so the
  baselines have to state the scale at which that stops being true.
- Security qualification: final threat-model review, boundary tests, extension
  isolation tests, policy bypass tests, artifact sanitization, offline
  qualification (M6-05).
- Experience: guided initialization, examples by failure class, actionable
  diagnostics, searchable HTML timeline, CI examples, self-hosting and plugin
  authoring documentation (M6-06).

Gate: the roadmap's 1.0 exit gate, plus the compatibility tests that make
"fixes only" enforceable rather than aspirational.

### 2.0 — Distributed execution

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

## 4. Standing gates

Two rules apply to every release above. They are here because the same thing
happened three times, not because they sound prudent.

**A guarantee without a failing test is a wish.** Every defect found reviewing
0.5 contradicted a sentence in an accepted ADR — generated values satisfy their
schema, faults always roll back, a scenario cannot widen the operator's
bounds. The sentences were written honestly and the code drifted quietly.
Before a release is cut, every guarantee an ADR states must have a test that
fails when it is violated; a guarantee that cannot be tested should not be
stated.

**A capability is not done until it has run end to end.** The unit suite stayed
green while a `$ref` inside an asserted schema had nothing to resolve it, a
`wait` in cleanup ran in the main phase, and destructive operations could not
be invoked at all. Each surfaced on the first real run against a real service.
Whatever the suite says, a new capability is exercised through the packaged
artifact against a running target before it is called complete.

## 5. After 1.0

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

## 6. Why the freeze falls after the runner

0.7 and 0.8 together are comparable in size to everything built between 0.2 and
0.5. M5 is comparable again on its own: a controller, a scheduler with leases,
a worker, aggregation, quotas, and a qualification suite are a distributed
system, not a feature. Putting it before the freeze would mean stabilising the
contracts at the moment the newest and least exercised part of the system had
just arrived.

Cutting 1.0 at the runner instead means the contracts freeze after the scenario
language has been in real use through four releases. The product's
differentiator — a business invariant verified across HTTP, events, and a
database, under injected faults, reproducibly — is complete at 0.8, and the
private-network runner at 0.9 makes it deployable where such systems live.
Distributed sharding matters to teams whose suites outgrow one machine, which
is a problem adopters get after the tool is in CI, not before.

One assumption underlies keeping M4 inside 1.0: that a run is worth triggering
from outside the protected segment before a controller exists — CI in one
network, targets in another, which is ordinary in regulated environments. If
that turns out not to describe real users, M4 belongs beside M5 in the 2.0
line, and 1.0 freezes on the local and CI story alone.
