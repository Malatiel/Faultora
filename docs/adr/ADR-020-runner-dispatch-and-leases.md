# ADR-020: What is dispatched to a runner, and what keeps it bounded

## Status

Accepted

## Context

M4 puts a runner inside a private network. The constraint that shapes
everything is in the exit gate: **no inbound connection into the private
network**, and **disconnection cannot extend a run or an active fault beyond
policy**. The first says who dials whom. The second says the runner must be
able to stop itself while it can hear nothing.

Architecture §15 requires a decision for the distributed task transport and the
lease model before implementation reaches them. This is that decision.

Two facts constrain it. The controller does not exist — it is 2.0 — so 0.9
builds the runner and the smallest counterpart that can dispatch to it. And
1.0 freezes whatever this protocol turns out to be, while its real consumer
arrives afterwards and will want shard descriptors, scheduling hints, and
shard-level leases.

## Decision

- **The runner dials out and asks for work.** It opens a TLS connection to the
  control plane, registers, and long-polls for a dispatch; progress, results
  and artifacts go back over connections it opened. Nothing listens inside the
  private network, which is the gate stated as a shape rather than a promise.
- **HTTP/1.1 over TLS, on the JDK.** The runner uses `java.net.http`, the
  counterpart `jdk.httpserver`. No second framing to freeze at 1.0, no
  dependency an offline bundle has to carry, and a long poll is enough for a
  runner executing one plan at a time. This is a decision about 0.9 and 1.0;
  2.0's controller may add a transport beside this one under protocol
  negotiation, which is what negotiation is for.
- **The dispatch carries the run's inputs, not the compiled plan.** A dispatch
  is the scenario source, the description documents it was compiled against in
  the order they were named, the runtime input values, the target redirects,
  the effective policy, the seed, and the run id. The runner compiles it with
  the same compiler and executes exactly that plan.

  The last two of those are easy to forget and neither is covered by a digest.
  `--input` values bind into the expression context and are not in the scenario
  source, so a dispatch without them compiles a scenario whose
  `{{inputs.currency}}` names nothing — which now fails the step rather than
  quietly emptying (ADR-018). `--target` redirects live in the connector
  configuration rather than in the policy, so a dispatch without them reaches
  the host the document happens to name. **The digests cover the documents; the
  values a run was parameterized with travel explicitly.** Two dispatches with
  identical digests and different inputs are different runs.

  The documents travel in the fixed order the loader uses — openapi, asyncapi,
  observations — because the catalog digest is taken over their digests in the
  order they were named. Carrying them in something that iterates differently
  would fail the digest check on correct dispatches, intermittently.

  The alternative — shipping the compiled `ExecutionPlan` — reads more faithful
  to architecture principle 1, and it was measured rather than argued about: a
  `PlanNode` serializes today and **cannot be read back**, because the sealed
  hierarchy carries no type information. Shipping it means annotating nine node
  kinds and everything they hold, and then freezing all of it as wire contract
  at 1.0 — a far larger permanent surface than four documents, at the moment
  the contract is supposed to stop growing.

  Principle 1 is honoured in substance: same compiler, same inputs, same seed,
  and the digests prove the inputs matched.
- **A digest mismatch is a refusal, not a warning.** The dispatch states the
  scenario and catalog digests the control plane computed. The runner computes
  its own and refuses to execute when they differ. A run whose inputs cannot be
  shown to be the ones asked for is not the run that was asked for.
- **A dispatch carries a lease, and the lease is the run's permission to
  exist.** It has a deadline. The runner executes only while the lease is
  unexpired, renews it through heartbeats, and when renewal fails it stops the
  run and rolls back faults **without being told to**. Enforcement that needed
  a message from the other side would not meet a gate about disconnection.
- **The lease does not add a clock; it lowers the bound the run already has.**
  A run is bounded by its scenario deadline and by the policy's wall-clock
  budget, and a fault by the watchdog's hard expiry. The lease joins that
  minimum: the effective bound is `min(lease, scenario deadline, policy
  budget)`, and lease expiry drives the cancellation path that already exists
  — the same one a scenario deadline uses, ending nodes as `CANCELLED` and
  running cleanup. A second stop mechanism beside it could disagree with the
  watchdog about when a fault ends, and a fault outliving its run is the worst
  version of the two-clocks defect that produced the event-window bug in 0.7.
- **Progress is the run journal.** The runner streams the `RunEvent`s it
  already writes, and the counterpart appends them to a journal of the same
  shape. A second event schema for the same facts is a second thing to keep
  true.

  This is not in tension with refusing to ship the compiled plan, although both
  are sealed hierarchies. `RunEvent` is **already** a durable artifact format:
  the NDJSON journal is written to disk, read by the report renderers, and
  compared between runs. It is committed whether or not a runner exists.
  `PlanNode` is internal, and putting it on a wire would newly freeze it.
- **Events survive a disconnection, and are delivered afterwards.** The runner
  writes its journal to a local working directory as it always does, and the
  events the counterpart has not acknowledged are re-sent on reconnect,
  identified by their position in the journal. Delivery is at-least-once and
  the position is what makes it idempotent. A runner that stopped cleanly and
  lost what it had already learned would fail the gate from the other side: the
  reason a run is bounded is so its findings survive, not so they end tidily.
  This is why the runner needs a writable working directory, which its
  packaging must provide.
- **The counterpart in 0.9 is qualification scaffolding, and is named so.** It
  lives in the test kit, not in a module called controller, and this ADR says
  plainly which half is frozen: **the runner-facing protocol is contract; the
  dispatcher that speaks it here is not.** 2.0 replaces the dispatcher and
  keeps the protocol — that is the whole point of writing them separately.

## Rejected alternatives

- **The controller dials the runner.** The simplest thing to build and the one
  thing the gate forbids: it requires an inbound path into the private network,
  which is the deployment shape M4 exists to avoid.
- **gRPC or WebSocket.** Both are better at bidirectional streaming than a long
  poll. Both add a dependency and a framing that 1.0 would freeze, for traffic
  that is one dispatch, a heartbeat every few seconds, and a stream of events.
- **Shipping the compiled plan.** Reviewed above: measured, and it freezes a
  sealed hierarchy as wire format.
- **Trusting the control plane's digests without recomputing.** It would save
  the runner an import. It would also mean the runner cannot tell a dispatch
  that was tampered with from one that was not, which is the property the
  digests exist for.
- **A lease renewed by the runner rather than granted.** Self-renewal makes the
  lease a comment. The deadline has to come from the side that can revoke it.

## What this does not yet carry

- **An evidence policy.** The signed policy is a `TargetPolicy`, which has no
  evidence dimension, so a dispatch cannot say how much of what a run sees may
  be kept. The runner uses the same default the CLI does, in one place both
  read, because the alternative was found the hard way: it started on the
  strictest possible policy, and every `row-balance` and every `jsonpath` over
  a response body would have come back indeterminate from a runner while
  passing on the machine the scenario was written on. **How much evidence a run
  may hold is a limit an operator should be able to state**, so this has to
  reach the protocol before 1.0 freezes it — as part of the signed policy,
  where a runner can narrow it like everything else.

## Consequences

- The runner needs the importers and the compiler, so it is not a thin agent.
  That is the cost of shipping inputs rather than a plan, and it is the reason
  local and remote runs cannot drift: they run the same code over the same
  bytes.
- A scenario that a runner cannot compile fails on the runner rather than at
  dispatch. The digest check catches a mismatch; it does not catch a runner
  whose extension set differs, which is what capability advertisement at
  registration is for.
- Long-polling holds a connection open per idle runner. At the scale 0.9
  targets — a runner per private network, not a fleet — that is a socket, not a
  problem. If a controller ever needs thousands, protocol negotiation is where
  the second transport arrives.
- **A poll is bounded at 30 seconds** and then reopened. The number is not
  about load: the runner re-reads its key material when it opens a connection,
  so the length of a poll is the worst case for how long a rotated certificate
  takes to be used.
