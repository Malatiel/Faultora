# Why Faultora exists

This page says what problem Faultora is meant to solve, for whom, what it
deliberately does not do, and what is not yet proven. Each capability named
here is in the 0.12.0 release and links to where it is documented or
tested. A statement about who needs it
is marked as a hypothesis until users outside the project have confirmed it.

## The problem

A service can pass every functional test and still break the promises its
business depends on, because those tests send one request at a time to a
service that is up. The promises break in other conditions:

- a client retries after a timeout and the payment is taken twice;
- two requests race for the last seat and both get it;
- a step fails half-way and leaves what the first half created behind;
- an event is published twice, or never, or before the row it describes;
- a dependency slows down and the service answers with something it should not.

These are not crashes. Each request gets a plausible answer; the damage shows
only when several steps, several clients or several components are looked at
together, and often only while something is going wrong. Today they tend to be
checked by hand, by one-off scripts nobody maintains, or by an incident.

## What Faultora does about it

Faultora runs **reliability scenarios** against a service from the outside,
using its API description, and checks **business invariants** — statements such
as "one idempotency key creates exactly one payment" — rather than only status
codes.

- **Describe once, from what you already have.** Operations come from OpenAPI
  and AsyncAPI documents, and from an operator-declared catalog of read-only
  database queries. `faultora init` writes a starter scenario from an OpenAPI
  document.
- **Create the conditions under which promises break.** Scenarios combine
  sequential steps, genuinely concurrent `parallel` groups, `repeat` batches,
  `eventually` polling for asynchronous state, retries and deadlines, and
  **bounded faults**: latency, errors and lost responses on Faultora's own
  requests, or network faults through a [Toxiproxy](https://github.com/Shopify/toxiproxy)
  you operate. While the Faultora process lives, every fault has a hard expiry
  and a rollback, and a rollback that fails or cannot be confirmed makes the
  run an error, never a pass. Faults on Faultora's own requests end with its
  process; a network fault is a toxic on a Toxiproxy proxy, so if the process
  is killed the operator removes leftover toxics, as the
  [scenario reference](SCENARIO_REFERENCE.md) describes.
- **Check what the business cares about, across components.** Assertions read
  HTTP responses, Kafka events and database rows, and their parameters are
  expressions over earlier steps, so "the ledger entries booked for this
  payment sum to zero" can be written down
  ([`settlement-invariant.yaml`](../examples/payment-recovery/scenarios/settlement-invariant.yaml)).
  [`fault-concurrent-duplicate.yaml`](../examples/payment-service/scenarios/fault-concurrent-duplicate.yaml)
  is a complete example: two concurrent payments with one idempotency key under
  injected latency, and the invariant that exactly one payment exists.
- **Leave evidence someone can review.** A run writes a journal of what
  happened and console, JSON, HTML and JUnit reports. Generated request values
  and retry jitter derive from a seed, so a failing run's inputs can be
  reproduced.
  The [output contract](OUTPUT_CONTRACT.md) is frozen, so CI and other tools can
  depend on it.
- **Stay inside your boundary.** Nothing is sent to a hosted service: no
  telemetry, no update check, no license server. Inside a network nothing can
  reach into, a [runner](RUNNER_PROTOCOL.md) dials out for work over mutually
  authenticated TLS. Losing contact stops the run on the runner's own clock
  and starts the rollback of its faults; a rollback attempt is not reported as
  confirmed recovery, and a forced termination can cut it short
  ([ADR-024](adr/ADR-024-bounded-cleanup.md)).
- **Let an assistant help without handing it the keys.** Over
  [MCP](MCP.md), an AI assistant can inspect the catalogs an operator declared
  and prepare scenarios; executing one needs the operator's separate signature on
  that exact preparation.

The [examples](../examples/README.md) show each of these against small
reference systems, and the payment recovery system proves invariants across
HTTP, Kafka and a database, each scenario also run against a variant built to
break the property it checks — and failing there.

## Who it is for

*Hypothesis until confirmed by users outside the project (see "What is not yet
proven").*

- **Backend, QA and SRE engineers responsible for services whose correctness
  under failure matters** — payments, orders, bookings, inventory, anything
  where a duplicate or a lost update costs money or trust — who want those
  properties checked in CI rather than discovered in production.
- **Teams in closed or regulated environments** that cannot send API
  descriptions, traffic or results to a hosted product. This is the design
  constraint the [security architecture](SECURITY.md) starts from: the default
  deployment must be acceptable in a closed financial-services environment.
- **Teams using AI assistants** who want an assistant to draft and run such
  checks within limits an operator sets, rather than with a shell and a
  credential.

## Decisions that follow from this

| Decision | Why |
|---|---|
| Black box, from an API description | No change to the service under test; the description is usually already there. |
| Invariants, not just contracts | Contract checks say a response has the right shape; the damage above has the right shape. |
| Faults only on Faultora's own requests, or a proxy you run | No privileges on the target or its infrastructure. A network fault acts on every connection through the named Toxiproxy proxy, not only the run's, so that proxy should carry test traffic alone. |
| Private and destructive targets refused by default | A wrong URL or a careless scenario should fail closed, not hit something real. `--allow-private` and `--allow-destructive` say so explicitly. |
| A frozen scenario format, `faultora.dev/v1` | A suite written today keeps working; the rule is enforced by a committed snapshot. |
| Evidence minimization | Reports, journals and diagnostics carry structural facts and fixed error text, not the values the evidence policy withholds. |
| Self-hosted only | The constraint above; no profile depends on a Faultora-operated service. |

## When Faultora is the wrong tool

- **Load and throughput benchmarking.** Faultora creates concurrency to expose
  races, within per-run budgets; it is not built to find how many requests per
  second a system sustains.
- **Infrastructure chaos.** It does not kill pods, partition nodes or fill
  disks. Its faults act on its own requests or through a Toxiproxy on the
  traffic path.
- **Experiments in production.** Runs are bounded and attempt to roll back
  their faults, but nothing restores the target after an arbitrary
  interruption; the product is designed for test and staging environments,
  and the defaults refuse what looks unsafe.
- **Exhaustive input fuzzing for crashes or security flaws.** Request values
  can be generated from schemas, deterministically, but the goal is a
  repeatable scenario, not covering the input space.
- **Consumer–provider contract compatibility across versions, UI testing.**
  Other categories of tool exist for these.

## Neighbouring kinds of tool

Faultora overlaps with several kinds of tool and replaces none of them
outright. Each of them goes further than Faultora in its own specialty, and
several cover part of what Faultora does:

- **API test runners** send requests and check responses; Faultora does that
  too, with concurrency, faults and invariants over several components as its
  focus.
- **Schema-driven property testing** generates many inputs, and some tools
  also explore sequences of operations
  ([Schemathesis stateful testing](https://schemathesis.readthedocs.io/en/stable/explanations/stateful/),
  for example). Faultora runs a sequence its author chose, repeatably, and
  checks a stated invariant.
- **Chaos engineering tools** describe experiments as a steady-state
  hypothesis, method and probes, usually against infrastructure
  ([Chaos Toolkit experiments](https://chaostoolkit.org/reference/api/experiment/),
  for example). Faultora's faults are on its own requests or a test proxy,
  and its checks are business assertions across components.
- **Load testing tools** measure capacity and can assert on responses and
  thresholds ([k6 checks](https://grafana.com/docs/k6/latest/using-k6/checks/),
  for example). Faultora uses overlap to expose correctness defects, not to
  find capacity.
- **Network fault proxies** such as Toxiproxy are a building block
  ([toxic fields](https://github.com/Shopify/toxiproxy#toxic-fields)); Faultora
  drives one when it is there.

What is particular to Faultora is the combination — invariants across HTTP,
events and databases, under bounded faults and real concurrency, with
reviewable evidence, self-hosted — rather than any one part.

## What is not yet proven

Faultora is a technical preview (0.12.0). Before 1.0:

- **Whether a person new to Faultora gets to a first useful result unaided**
  has not been measured. In a first trial an AI assistant with no prior
  context, given only the MCP server and these documents, found a defect
  planted in a service the project had never seen, and showed where the tool
  got in its way; that is one trial, not a measure.
- **Whether the people described above need this** has not been confirmed by
  anyone outside the project.
- **Third-party extensions do not yet run isolated** from the Faultora process;
  a policy that requires isolation is refused rather than pretended
  ([ADR-023](adr/ADR-023-extension-isolation.md)).
- **A runner does not yet deliver after a restart** what it had not sent
  before. Since 0.12.0 it commits that evidence to a local spool first, as
  the first step towards such delivery
  ([ADR-031](adr/ADR-031-runner-recovery-ownership.md),
  [runner spool](RUNNER_SPOOL.md)), but nothing is replayed after a restart.
- **Distributed workers** belong to 2.0 ([release plan](RELEASE_PLAN.md)).

If you try Faultora on your own service, what got in your way is the most
useful thing you can report.
