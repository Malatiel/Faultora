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

## Status after 0.8.0

Ticked items are met by `examples/payment-recovery` and the eight runs of
`CrossComponentE2ETest`. `[~]` is met in part, `[ ]` is not built, and each of
those carries its reason below — recorded here rather than quietly dropped,
because a checklist that only ever gains ticks stops being one.

- **Nothing shared across threads (§0).** Each component owns its clients and
  opens a connection per unit of work, and this is stated in every javadoc. The
  gate scenarios are sequential, though: the parallel path is exercised by the
  suites that already cover it, and no gate scenario needed it.
- **Cleanup as an obligation (§0).** Faultora may only read this database and
  the API has no delete, so a scenario cannot remove what it created without a
  write path built for the test's convenience — which §7 of this list forbids.
  The harness empties the tables between runs and every observation is scoped
  to one payment, so a leftover row is readable by nobody. A cleanup obligation
  belongs with a delete operation the product would want for its own sake.
- **The simulator's state in the observation catalog (§4).** The provider keeps
  its charges in memory and answers over HTTP, which is what makes it external:
  a simulator sharing the ledger's database would let a scenario pass by
  reading what the test itself wrote. Its state is reachable through Faultora's
  HTTP connector; it is not reachable through a `row-*` assertion.
- **Injectable latency (§4).** Not built. No gate scenario needs the provider
  slow rather than silent, and a delay nobody asserts on is a knob to maintain.
- **Reconciliation on demand (§5).** It runs every 200 ms inside a 30 s
  convergence budget, which is deterministic in the sense the gate needs. An
  endpoint to trigger it would be a control surface existing only for tests.
- **Reconciliation proved idempotent by a scenario (§5).** It *is* idempotent —
  it books through the same claim key the consumer uses, so a payment settled
  after all is not booked twice — but no gate scenario runs it twice and
  asserts nothing changed. The property is implemented and untested.
- **Response-lost selected per request (§4).** The scenario selects it by
  choosing the variant, not by a flag on the request. No gate scenario needs
  two providers behaving differently within one run.

## 0. Rules that apply to every component

- [x] **Every stated guarantee carries the test that fails when it is
  violated.** A bound documented in README/docs/javadoc but enforced nowhere
  is deleted, not kept. *(0.6 discipline; restated in Phase 5; violated by
  `EvidencePolicy.maxRows` from 0.1 to 0.8.)*
- [x] **The default build runs offline.** No container, broker, or database is
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
- [x] **Configuration selects failure variants; defaults are the healthy
  system.** A variant is chosen by an explicit flag/env, never by code path
  someone has to remember to uncomment. *(RELEASE_PLAN Phase 4.)*

## 1. Domain model

- [x] **Payment has a business identifier the scenario controls** (client
  supplied, e.g. `paymentId` from the request), so a scenario can assert
  idempotency across duplicate submissions without reading a generated ID.
- [x] **Ledger entries are double-entry from the first commit** — every
  booking writes balanced rows — so `row-balance … equals: 0` is a property
  of the system, not of the test data. *(row-balance exists for exactly this,
  M3-04.)*
- [x] **Amounts are stored as a decimal type, never float.** A `double`
  column would make the ledger's own numbers unassertable. *(NaN/infinity
  handling in `ObservedRows.number` exists because floats are not amounts.)*
- [x] **Nullable columns are nullable deliberately.** The gate scenarios
  include at least one observation over a column that can hold NULL, so
  uniqueness assertions meet the SQL semantics they implement. *(row-unique
  NULL handling, 0.8 review finding #7.)*

## 2. Transactional outbox

- [x] **Business write and outbox write commit in one transaction.** This is
  the invariant the whole pattern exists for; a test kills the process between
  the two (fault variant) and the reconciler — not a hand-check — is what
  proves nothing was lost.
- [x] **Outbox rows carry a monotonic anchor a scenario can observe
  deterministically** (sequence or timestamp the contract declares), so the
  observation window is stated rather than guessed. *(ADR-014: what guessing
  the window costs; RELEASE_PLAN Phase 1.)*
- [x] **The relay marks dispatched rows in the same transaction as the
  publish where the broker allows it, and the at-least-once consequence is
  documented**: duplicates happen, and the consumer — not the relay — absorbs
  them.

## 3. Idempotent consumer

- [x] **Deduplication keys on the business identifier, not the broker
  offset.** A redelivery after rebalance must hit the same idempotency check
  as an at-least-once duplicate. *(Duplicate delivery produces one business
  effect — M3 gate.)*
- [x] **The duplicate path is observable**: the consumer records that it saw
  a repeat, so a scenario can assert "processed once, seen twice" rather than
  inferring it from the ledger alone.
- [x] **The consumer's write and its dedup record commit atomically.** The
  broken variant splits them; the gate scenario against that variant must
  fail. *(Known-broken variants must actually break.)*

## 4. Provider simulator

- [~] **Accepted-but-response-lost is a first-class behaviour**: the provider
  applies the charge and then drops/timeouts the response, selectable per
  request (header or payload flag), so the scenario — not the simulator's
  operator — chooses when it happens. *(RELEASE_PLAN Phase 4.)*
- [ ] **The simulator's state is queryable through the same observation
  mechanism as everything else** (an entry in the observation catalog), so
  reconciliation assertions use `row-*` like the rest of the gate.
- [ ] **Latency is injectable without code changes** (fixed delay and
  delay-then-fail modes), because the reconciliation scenario needs the
  provider to be slow, not broken, in one variant.
- [x] **The simulator never writes to the ledger.** It is an external system;
  coupling it to the ledger would let a test pass by reading what it wrote
  itself. *(A reliability test that has never failed proves nothing — the
  gate must be able to fail.)*

## 5. Reconciliation worker

- [ ] **Reconciliation is triggerable on demand** (endpoint or CLI command),
  not only on a timer — a scenario cannot wait for a schedule and stay
  deterministic. *(Deterministic observation windows, M3 gate.)*
- [x] **It resolves an unknown provider outcome both ways** — charge found at
  the provider → book it; charge absent → mark failed — and each resolution
  leaves a queryable record. *(M3 gate: a reconciliation run resolves an
  unknown provider outcome.)*
- [~] **Reconciliation is itself idempotent**: running it twice over the same
  window changes nothing the second time, and a scenario proves it.

## 6. Gate scenarios (the deliverable this system exists for)

Each scenario below ships in pairs: against the healthy system it passes,
against its known-broken variant it fails. Both halves are committed and run.

- [x] **Command → event → balance.** One HTTP command, the event it causes
  observed through the Kafka path, the ledger proven balanced through a
  JDBC observation with `row-balance`. *Broken variant: consumer books only
  one side of the entry.*
- [x] **Duplicate delivery, one business effect.** The same command delivered
  twice (retried HTTP and/or redelivered event) produces one payment and a
  balanced ledger; `row-unique` on the business identifier holds. *Broken
  variant: consumer without the dedup record.*
- [x] **Lost outbox event is detected.** The relay's broken variant drops a
  row; the scenario's observation window expires; the assertion on the
  expected event fails — and against the healthy system the event is
  observed within the declared window. *(ADR-014 lesson: the window design
  must be disprovable.)*
- [x] **Reconciliation resolves the unknown.** Provider accepts and loses the
  response; the run reconciles; the ledger balances and the payment reaches
  its terminal state. *Broken variant: reconciler that only looks one way.*
- [x] **Each broken variant is selected by configuration and documented in
  one place** — a table of variant → defect → scenario that catches it.
  *(RELEASE_PLAN Phase 4; discoverability is what keeps variants honest.)*

## 7. Observability of the reference system itself

- [x] **Everything a gate scenario asserts is observable through Faultora's
  own mechanisms** — HTTP connector, Kafka evidence, JDBC observations — with
  no backdoor assertion endpoint added for the tests' convenience. If the
  reference system needs a backdoor, the product has a gap; record it as one.
- [x] **The observation catalog for the reference system is committed beside
  it** and is the example `docs/SCENARIO_REFERENCE.md` points at, so the
  documented example and the gate cannot drift apart. *(Docs checked against
  code, Phase 5.)*
- [x] **Row limits in the gate's evidence policy are stated explicitly**, so
  a truncated ledger read is a deliberate choice, not a surprise reported as
  indeterminate. *(truncated semantics, M3-03.)*

## 8. Build, size, and release hygiene

- [x] **The reference system is a separate Maven module (or modules) that the
  CLI does not depend on.** Nothing of it lands in the shaded artifact.
  *(The shaded jar's size is a baseline, not a surprise — 0.7 zstd lesson.)*
- [x] **The PostgreSQL driver joins the size baseline as a number** in the
  release notes, per the Phase 5 commitment made in this cycle.
- [x] **Real-infrastructure gate runs are scripted once** (`docker compose`
  or equivalent, a single command, teardown included) and the script — not a
  wiki page — is the instructions. *(Disposable infrastructure, Phase 4.)*
- [x] **New claims audit**: any README/docs/ADR sentence added with the
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
