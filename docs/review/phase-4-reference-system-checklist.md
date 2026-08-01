# Phase 4 checklist — the payment recovery reference system

A review-driven requirements list for the system the M3 exit gate runs against.
Every item is phrased so it can be checked in code or by a test, and annotated
with the lesson that produced it. Sources: the 0.6 claims-audit discipline, the
three events-release reviews (0.7.x), the JDBC-observation review (0.8.0-SNAPSHOT),
and the Phase 5 commitments already in `docs/RELEASE_PLAN.md`.

Scope (per RELEASE_PLAN): transactional outbox, idempotent consumer,
double-entry ledger, provider simulator with accepted-but-response-lost
behaviour, reconciliation worker, failure variants by configuration.
Disposable test infrastructure; the default build stays offline.

## 0. Rules that apply to every component

- [ ] **Every stated guarantee carries the test that fails when it is
  violated.** A bound documented in README/docs/javadoc but enforced nowhere
  is deleted, not kept. *(0.6 discipline; restated in Phase 5; violated by
  `EvidencePolicy.maxRows` from 0.1 to 0.8.)*
- [ ] **The default build runs offline.** No container, broker, or database is
  required for `./mvnw test`. Real-infrastructure runs are an opt-in profile
  with a name that says so. *(RELEASE_PLAN Phase 4; the events release's most
  serious defect passed every unit test and was visible only against a real
  broker.)*
- [ ] **Nothing is shared across threads without a stated owner.** Each
  component declares what it shares (connections, consumers, in-memory
  state), and the gate scenarios exercise the parallel path, not only the
  sequential one. *(Kafka consumer driven by two steps of a parallel group,
  0.7.x.)*
- [ ] **Cleanup is an obligation, not a hope.** Every scenario resource —
  rows, topics, provider state — has a registered cleanup that runs on failure
  paths too, and the journal shows it. *(CleanupNode obligations; M3 gate
  requires cleanup.)*
- [ ] **Configuration selects failure variants; defaults are the healthy
  system.** A variant is chosen by an explicit flag/env, never by code path
  someone has to remember to uncomment. *(RELEASE_PLAN Phase 4.)*

## 1. Domain model

- [ ] **Payment has a business identifier the scenario controls** (client
  supplied, e.g. `paymentId` from the request), so a scenario can assert
  idempotency across duplicate submissions without reading a generated ID.
- [ ] **Ledger entries are double-entry from the first commit** — every
  booking writes balanced rows — so `row-balance … equals: 0` is a property
  of the system, not of the test data. *(row-balance exists for exactly this,
  M3-04.)*
- [ ] **Amounts are stored as a decimal type, never float.** A `double`
  column would make the ledger's own numbers unassertable. *(NaN/infinity
  handling in `ObservedRows.number` exists because floats are not amounts.)*
- [ ] **Nullable columns are nullable deliberately.** The gate scenarios
  include at least one observation over a column that can hold NULL, so
  uniqueness assertions meet the SQL semantics they implement. *(row-unique
  NULL handling, 0.8 review finding #7.)*

## 2. Transactional outbox

- [ ] **Business write and outbox write commit in one transaction.** This is
  the invariant the whole pattern exists for; a test kills the process between
  the two (fault variant) and the reconciler — not a hand-check — is what
  proves nothing was lost.
- [ ] **Outbox rows carry a monotonic anchor a scenario can observe
  deterministically** (sequence or timestamp the contract declares), so the
  observation window is stated rather than guessed. *(ADR-014: what guessing
  the window costs; RELEASE_PLAN Phase 1.)*
- [ ] **The relay marks dispatched rows in the same transaction as the
  publish where the broker allows it, and the at-least-once consequence is
  documented**: duplicates happen, and the consumer — not the relay — absorbs
  them.

## 3. Idempotent consumer

- [ ] **Deduplication keys on the business identifier, not the broker
  offset.** A redelivery after rebalance must hit the same idempotency check
  as an at-least-once duplicate. *(Duplicate delivery produces one business
  effect — M3 gate.)*
- [ ] **The duplicate path is observable**: the consumer records that it saw
  a repeat, so a scenario can assert "processed once, seen twice" rather than
  inferring it from the ledger alone.
- [ ] **The consumer's write and its dedup record commit atomically.** The
  broken variant splits them; the gate scenario against that variant must
  fail. *(Known-broken variants must actually break.)*

## 4. Provider simulator

- [ ] **Accepted-but-response-lost is a first-class behaviour**: the provider
  applies the charge and then drops/timeouts the response, selectable per
  request (header or payload flag), so the scenario — not the simulator's
  operator — chooses when it happens. *(RELEASE_PLAN Phase 4.)*
- [ ] **The simulator's state is queryable through the same observation
  mechanism as everything else** (an entry in the observation catalog), so
  reconciliation assertions use `row-*` like the rest of the gate.
- [ ] **Latency is injectable without code changes** (fixed delay and
  delay-then-fail modes), because the reconciliation scenario needs the
  provider to be slow, not broken, in one variant.
- [ ] **The simulator never writes to the ledger.** It is an external system;
  coupling it to the ledger would let a test pass by reading what it wrote
  itself. *(A reliability test that has never failed proves nothing — the
  gate must be able to fail.)*

## 5. Reconciliation worker

- [ ] **Reconciliation is triggerable on demand** (endpoint or CLI command),
  not only on a timer — a scenario cannot wait for a schedule and stay
  deterministic. *(Deterministic observation windows, M3 gate.)*
- [ ] **It resolves an unknown provider outcome both ways** — charge found at
  the provider → book it; charge absent → mark failed — and each resolution
  leaves a queryable record. *(M3 gate: a reconciliation run resolves an
  unknown provider outcome.)*
- [ ] **Reconciliation is itself idempotent**: running it twice over the same
  window changes nothing the second time, and a scenario proves it.

## 6. Gate scenarios (the deliverable this system exists for)

Each scenario below ships in pairs: against the healthy system it passes,
against its known-broken variant it fails. Both halves are committed and run.

- [ ] **Command → event → balance.** One HTTP command, the event it causes
  observed through the Kafka path, the ledger proven balanced through a
  JDBC observation with `row-balance`. *Broken variant: consumer books only
  one side of the entry.*
- [ ] **Duplicate delivery, one business effect.** The same command delivered
  twice (retried HTTP and/or redelivered event) produces one payment and a
  balanced ledger; `row-unique` on the business identifier holds. *Broken
  variant: consumer without the dedup record.*
- [ ] **Lost outbox event is detected.** The relay's broken variant drops a
  row; the scenario's observation window expires; the assertion on the
  expected event fails — and against the healthy system the event is
  observed within the declared window. *(ADR-014 lesson: the window design
  must be disprovable.)*
- [ ] **Reconciliation resolves the unknown.** Provider accepts and loses the
  response; the run reconciles; the ledger balances and the payment reaches
  its terminal state. *Broken variant: reconciler that only looks one way.*
- [ ] **Each broken variant is selected by configuration and documented in
  one place** — a table of variant → defect → scenario that catches it.
  *(RELEASE_PLAN Phase 4; discoverability is what keeps variants honest.)*

## 7. Observability of the reference system itself

- [ ] **Everything a gate scenario asserts is observable through Faultora's
  own mechanisms** — HTTP connector, Kafka evidence, JDBC observations — with
  no backdoor assertion endpoint added for the tests' convenience. If the
  reference system needs a backdoor, the product has a gap; record it as one.
- [ ] **The observation catalog for the reference system is committed beside
  it** and is the example `docs/SCENARIO_REFERENCE.md` points at, so the
  documented example and the gate cannot drift apart. *(Docs checked against
  code, Phase 5.)*
- [ ] **Row limits in the gate's evidence policy are stated explicitly**, so
  a truncated ledger read is a deliberate choice, not a surprise reported as
  indeterminate. *(truncated semantics, M3-03.)*

## 8. Build, size, and release hygiene

- [ ] **The reference system is a separate Maven module (or modules) that the
  CLI does not depend on.** Nothing of it lands in the shaded artifact.
  *(The shaded jar's size is a baseline, not a surprise — 0.7 zstd lesson.)*
- [ ] **The PostgreSQL driver joins the size baseline as a number** in the
  release notes, per the Phase 5 commitment made in this cycle.
- [ ] **Real-infrastructure gate runs are scripted once** (`docker compose`
  or equivalent, a single command, teardown included) and the script — not a
  wiki page — is the instructions. *(Disposable infrastructure, Phase 4.)*
- [ ] **New claims audit**: any README/docs/ADR sentence added with the
  reference system is listed in the release PR and checked against the code,
  per Phase 5.

## 9. What Phase 4 explicitly does not build

Carried from RELEASE_PLAN "What 0.8 deliberately does not touch", so the
checklist does not grow the scope by accident:

- No runner/controller groundwork (0.9 and 2.0 own those).
- No new scenario-contract constructs beyond what the four gate scenarios
  need; anything missing is recorded as a 1.0-freeze decision, not invented
  here.
- No generic "test framework" abstractions around the reference system — it
  is disposable infrastructure, and reusability promises become a second
  thing to keep correct.
