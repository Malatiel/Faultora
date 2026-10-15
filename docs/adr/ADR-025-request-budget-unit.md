# ADR-025: Request budgets count connector attempts

## Status

Accepted

## Context

`TargetPolicy.maxRequests` already admits plans by their worst-case retry,
repeat and poll counts (ADR-011). The word request does not distinguish a
scenario step, a connector invocation and a physical network exchange. HTTP
redirects and Kafka/JDBC client work make those different quantities. Static
admission alone also leaves a manually assembled plan without a runtime guard.

## Decision

- The unit is a **connector attempt** across setup, execute and declared
  cleanup. Each retry and each eventually poll needs another attempt. A
  repeat charges each child's attempts in each iteration; parallel children
  share the run's atomic budget. Waits, assertions and fault lifecycle calls
  do not consume it.
- Charge after inputs, connector and target resolution, immediately before
  connector preparation. Preparation may open a connection. Once granted,
  the attempt stays charged even if preparation fails, the connector refuses
  the request, or cancellation arrives before `execute`. No refund is made.
  A refusal before this boundary, including an absent connector or target,
  consumes nothing. The metric measures granted attempts, not successful
  requests or proof of a packet being sent.
- Keep conservative compile-time admission: reserve every permitted attempt,
  including unused retries and polls. Runtime charges only attempts actually
  granted and refuses an extra attempt before preparation with nonretryable
  `POLICY_VIOLATION` / `REQUEST_BUDGET_EXCEEDED`. Exhaustion cannot satisfy
  `expectError` and is preserved as a policy failure by polling.
- Reserve one attempt per declared cleanup operation at run entry, within the
  same `maxRequests`. Main work cannot spend this reserve. Cleanup waits need
  no attempt. A malformed manual plan whose cleanup alone exceeds the policy
  receives at most the policy's permits; remaining obligations fail explicitly.
  Time budgets and fault rollback remain governed by ADR-024.
- HTTP redirects stay inside one attempt, under the connector's redirect and
  time limits. For example, GET followed by two redirects costs one attempt
  despite three exchanges. Retrying that operation once costs a second
  attempt. Kafka metadata, producer retransmission and observation fetches,
  and JDBC connection/query exchanges are also internal to their attempt.
- Journal each grant as `OPERATION_ATTEMPTED`, with node/operation IDs and a
  run-wide attempt number, before preparation; omit inputs, destinations and
  credentials. `RUN_STARTED.resolvedConfig.requestBudgetUnit` declares
  `connector-attempt`. The report metric counts grant events, including
  cleanup and failed preparation; retry scheduling and condition evaluation
  events are not substitutes for this count. Old journals without the marker
  or grant events report the metric as unknown. Journal I/O remains best effort.

## Rejected alternatives

- **One unit per logical step:** retries and polling could multiply work
  without consuming additional budget.
- **One unit per physical exchange:** the engine cannot enforce client-level
  exchanges through today's SPI. Counting only HTTP hops would give the same
  policy field inconsistent meanings for Kafka and JDBC. A separate transport
  budget requires its own contract and enforcement, rather than redefining this
  one silently.
- **Charge only success or refund failure:** failed connections and rejected
  calls still consume work; concurrent refunds would enable repeated attempts.
- **Give cleanup an additional request allowance:** that would widen the signed
  policy. Reserving its share preserves both the cap and cleanup obligations.
- **Infer attempts from retries plus completed nodes:** a scheduled retry may
  be cancelled during backoff, and one eventually node can make many attempts.

## Consequences

No YAML, TargetPolicy fields, connector SPI or result record shapes change.
The journal adds a discriminator following ADR-005's additive evolution rule;
old strict typed readers that do not recognize it require an updated model.
The policy is not a hard packet, HTTP-hop or broker-request ceiling. Runtime
admission also protects callers that bypass PlanCompiler, but does not make
runtime-sized loops or nested groups supported scenario syntax.
