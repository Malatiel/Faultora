# Payment recovery — the reference system

The system the M3 exit gate runs against: an HTTP command side, a transactional
outbox, a relay, an idempotent consumer, a double-entry ledger, a payment
provider that can take a charge and lose the response, and a worker that
reconciles what the lost response left behind.

It depends on nothing from Faultora. That is deliberate — the invariants the
gate proves are properties of an ordinary application, not of the tool that
observes it.

```
POST /payments ──┐
                 │  one transaction
                 ├──► payments ──────────┐
                 └──► outbox             │
                          │              │
                    relay │ at-least-once│
                          ▼              │
                  payment-commands ──► settlement consumer ──► provider
                                             │                    │
                              claim + ledger │ one transaction    │ charge
                                             ▼                    │
                                      ledger_entries ◄────────────┘
                                             ▲          reconciliation
                                             │          worker asks what
                                      payment-events    the provider knows
```

## The variants

Each variant removes exactly one property. Never two: a variant that broke two
things at once would let a scenario pass its gate for the wrong reason.

| Variant | What it removes | Scenario that catches it | How it fails |
|---|---|---|---|
| `singleEntryLedger()` | The credit side of every booking | `settlement-invariant.yaml` | `row-balance` sums to the amount instead of zero |
| `nonIdempotentConsumer()` | The claim key committed with the booking | `duplicate-delivery.yaml` | Two settlements, four entries, two events — while the ledger still balances |
| `nonTransactionalOutbox()` | The outbox row written with the payment | `lost-event-detected.yaml` | Nothing is ever booked; the convergence budget is spent |
| `lostProviderResponseAndNoReconciliation()` | The reconciliation worker | `reconciled-unknown-outcome.yaml` | The charge is taken and never booked; the convergence budget is spent |

`lostProviderResponse()` is **not** a broken variant. A provider that accepts a
charge and loses the response is ordinary; a payment system that cannot survive
it is the defect. The correct system runs the reconciliation scenario against
exactly that provider and passes.

## Running the gate

```bash
./mvnw verify -pl integration-tests
```

`CrossComponentE2ETest` starts a disposable PostgreSQL and Kafka through
Testcontainers, runs each scenario through the packaged CLI twice — once
against the correct system, once against its variant — and tears everything
down. With no container runtime the suite skips itself and says so, so the
default build stays offline.

## What the gate reads, and as whom

The observations live in [`observations.yaml`](observations.yaml) — the file
`docs/SCENARIO_REFERENCE.md` points at — and every one of them is scoped to a
single payment. Runs share a database, and an unscoped count would read another
run's rows and report a defect belonging to nobody.

They connect as `faultora_readonly`, a role holding `GRANT SELECT` and nothing
else. `docs/SECURITY.md` calls the grant the guarantee that survives a defect
in Faultora itself; running the gate as the owner would have left that the one
claim in the section nothing demonstrates.

The CLI's evidence policy keeps 1000 rows. The gate's observations return one
or two, so nothing here is truncated — stated because a truncated read is
reported as indeterminate rather than counted, and that should be a choice
rather than a surprise.

## Known deviations from the phase 4 checklist

`docs/review/phase-4-reference-system-checklist.md` asks for more than this
system does. What is missing, and why:

- **Cleanup is not a scenario obligation.** Faultora may only read this
  database, and the API has no delete, so a scenario cannot remove what it
  created without a write path built for the test's convenience — which the
  same checklist forbids. The harness empties the tables between runs, and
  every observation is scoped to one payment, so a leftover row can be read by
  nobody. A cleanup obligation belongs with a delete operation the product
  would want anyway.
- **Reconciliation runs on a timer, not on demand.** A 200 ms pass inside a
  30 s convergence budget is deterministic in the sense the gate needs; an
  endpoint to trigger it would be a control surface that exists only for tests.
- **The provider's lost response is chosen per system, not per request.** The
  scenario that needs it selects the variant; a per-request flag would let one
  scenario choose, and no gate scenario needs that yet.
- **The gate scenarios are sequential.** The parallel path is exercised by the
  suites that already cover it; nothing here is shared between steps.
