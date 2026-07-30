# Scenario reference

This page documents the scenario format implemented by Faultora 0.7.2. The
format is versioned independently from the application:

```yaml
apiVersion: faultora.dev/v1alpha1
kind: Scenario
```

Faultora rejects unsupported versions, missing required fields, duplicate step
IDs, unknown references, dependency cycles, and execution features that are not
available in 0.7.2.

## Complete example

```yaml
apiVersion: faultora.dev/v1alpha1
kind: Scenario

metadata:
  name: payment-smoke
  description: Create a payment and verify its response
  labels:
    team: payments
    suite: smoke
  annotations:
    owner: payments-platform

execute:
  - id: create-payment
    type: operation
    operationId: create-payment
    timeout: 10s
    inputs:
      body:
        amount: 2500
        currency: EUR
      headers:
        X-Correlation-ID: faultora-example

  - id: settle-delay
    type: wait
    timeout: 100ms
    dependsOn:
      - create-payment

assertions:
  - id: create-status
    assertionType: status
    targetStep: create-payment
    params:
      expected: 201

  - id: response-has-id
    assertionType: jsonpath
    targetStep: create-payment
    params:
      path: id
      exists: true

  - id: response-is-fast
    assertionType: duration
    targetStep: create-payment
    params:
      max: 1000

cleanup:
  - id: list-payments
    type: operation
    operationId: list-payments
```

Validate a document before running it:

```bash
java -jar faultora-0.7.2.jar validate --scenario scenario.yaml
```

## Top-level fields

| Field | Required | Description |
|---|---:|---|
| `apiVersion` | yes | Must be `faultora.dev/v1alpha1`. |
| `kind` | yes | Must be `Scenario`. |
| `metadata` | yes | Scenario identity and descriptive metadata. |
| `timeout` | no | Scenario deadline. Once it elapses no further step starts, cleanup still runs, and the run fails with `SCENARIO_DEADLINE_EXCEEDED`. |
| `inputs` | no | Input declarations, bound at runtime with `faultora test --input key=value` and available as `{{inputs.<name>}}`. |
| `setup` | no | Operation or wait steps executed before the main section. |
| `execute` | yes | Main operation or wait steps. Must contain at least one step. |
| `faults` | no | In-process fault injection steps. See [Faults](#faults). |
| `assertions` | no | Checks evaluated against operation evidence. |
| `cleanup` | no | Final operation or wait steps. |

An unknown top-level field produces a warning. Unknown fields inside typed
objects are rejected.

## Metadata

```yaml
metadata:
  name: payment-smoke
  description: Human-readable purpose
  labels:
    team: payments
    suite: smoke
  annotations:
    ticket: PAY-123
```

`metadata.name` is required and must not be blank. `description`, `labels`, and
`annotations` are optional.

## Input declarations

The document model accepts declarations such as:

```yaml
inputs:
  currency:
    type: string
    description: ISO 4217 currency code
    required: false
    defaultValue: EUR
```

Supported declaration types are `string`, `number`, `boolean`, and `object`.
Declared inputs bind at runtime: `faultora test --input key=value` supplies a
value (repeatable), declared `defaultValue`s fill the rest, a missing
`required` input is a configuration error, and an unknown `--input` name is
rejected. Bound inputs are available to templates as `{{inputs.<name>}}`.

## Operation steps

Operation steps may appear in `setup`, `execute`, and `cleanup`.

```yaml
- id: get-payment
  type: operation
  operationId: get-payment
  timeout: 5s
  dependsOn:
    - create-payment
  inputs:
    paymentId: pay-123
    verbose: true
    headers:
      X-Correlation-ID: faultora-example
```

| Field | Required | Description |
|---|---:|---|
| `id` | yes | Stable ID, unique across every scenario section. |
| `type` | no | Defaults to `operation`; may also be `wait`, `parallel`, `repeat`, or `eventually`. |
| `operationId` | for operations | Must match an OpenAPI `operationId`. |
| `inputs` | no | Path, query, header, and body values. |
| `dependsOn` | no | IDs that must complete successfully first. |
| `timeout` | no | Positive duration: milliseconds, `ms`, `s`, or `m`. |
| `outputAs` | no | Name binding the step's response for later steps. See [Expressions and step outputs](#expressions-and-step-outputs). |
| `retry` | no | Retry policy for retryable operation errors. See [Retries](#retries). |
| `generate` | no | Request values built from the operation's schemas. See [Generated request values](#generated-request-values). |
| `expectError` | no | When `true`, the step passes only if the operation fails with a normalized error, and its dependents still run. Use for requests executed under an injected fault. If the operation succeeds instead, the step fails with `EXPECTED_ERROR`. |
| `metadata` | no | Arbitrary step metadata. |

Operation timeout examples:

```yaml
timeout: 500
timeout: 500ms
timeout: 10s
timeout: 1m
```

The HTTP connector maps `inputs` as follows:

| Input shape | HTTP mapping |
|---|---|
| A key matching `{name}` in the OpenAPI path | URL-encoded path segment |
| `body` | JSON request body for `POST`, `PUT`, or `PATCH` |
| `headers` | Request header map |
| Any other non-null key | URL-encoded query parameter |

Example:

```yaml
inputs:
  paymentId: pay-123
  expand: history
  headers:
    X-Request-ID: smoke-001
  body:
    status: approved
```

String values anywhere in `inputs` — including inside nested `body` and
`headers` maps — participate in `{{...}}` template resolution against declared
inputs and bound step outputs. See
[Expressions and step outputs](#expressions-and-step-outputs).

## Expressions and step outputs

String values in step `inputs` may contain `{{expression}}` templates,
including inside nested maps and lists (`body`, `headers`). A value that is a
single template keeps its original type; mixed strings interpolate.

The expression context contains:

| Path | Content |
|---|---|
| `inputs.<name>` | Declared scenario inputs, resolved from `--input` values and declared defaults. |
| `steps.<name>.status` | HTTP status of the step bound with `outputAs: <name>`. |
| `steps.<name>.body` | Parsed JSON response body (present only when the evidence policy captures bodies). |
| `steps.<name>.headers` | Response headers, filtered by the evidence policy. |
| `steps.<name>.protocol` | What the protocol contributed: for events, `published` (topic, partition, offset), `messages`, and `message` — the first observed message, bound because expressions address objects rather than list positions. |
| `repeat.index`, `repeat.item` | Current iteration inside a [repeat step](#repeat-steps). |
| `run.seed`, `run.target` | Run metadata. |

```yaml
execute:
  - id: create-payment
    type: operation
    operationId: create-payment
    outputAs: created
    inputs:
      body:
        amount: 100

  - id: read-back
    type: operation
    operationId: get-payment
    dependsOn: [create-payment]
    inputs:
      paymentId: "{{steps.created.body.id}}"
```

Rules:

- only steps that declare `outputAs` are bound; the name must match
  `[A-Za-z_][A-Za-z0-9_-]*` and be unique in the scenario;
- outputs of failed steps are not bound;
- children of a parallel group are bound only after the whole group finishes,
  in declaration order — children never observe each other's outputs;
- expressions are read-only and never render secret-derived values.

## Parallel steps

A `parallel` step runs its child operation steps concurrently:

```yaml
execute:
  - id: race
    type: parallel
    dependsOn: [sync-delay]
    steps:
      - id: first-client
        type: operation
        operationId: create-payment
        inputs:
          headers:
            Idempotency-Key: "{{inputs.idempotency-key}}"
      - id: second-client
        type: operation
        operationId: create-payment
        inputs:
          headers:
            Idempotency-Key: "{{inputs.idempotency-key}}"
```

Semantics:

- children start together once the group's `dependsOn` is satisfied and run
  on a pool bounded by the policy's `maxConcurrency`; the group also fails
  compilation if it has more children than the policy allows concurrently;
- children are operation steps only — no nesting, no `wait`, no `dependsOn`
  between children; `retry`, `expectError`, `outputAs`, and `timeout` work per
  child;
- the group passes only when every child passes; all children always run to
  completion even if a sibling fails;
- child step IDs share the global namespace: assertions may target a child
  directly, and their execution is ordered after the whole group;
- every child (and each retry attempt) counts against the policy request
  budget.

## Repeat steps

A `repeat` step runs its child operation steps once per iteration:

```yaml
execute:
  - id: create-batch
    type: repeat
    forEach:
      - EUR
      - USD
      - GBP
    steps:
      - id: create-payment
        type: operation
        operationId: create-payment
        inputs:
          body:
            amount: 1200
            currency: "{{repeat.item}}"
          headers:
            X-Batch-Index: "{{repeat.index}}"
```

| Field | Required | Description |
|---|---:|---|
| `count` | one of | Fixed number of iterations, 1–100. |
| `forEach` | one of | Literal item list; one iteration per item, at most 100. |
| `steps` | yes | Child operation steps, run in declaration order every iteration. |
| `timeout` | no | Deadline for the whole group. |
| `dependsOn` | no | IDs that must complete before the first iteration. |

Semantics:

- exactly one of `count` and `forEach` is required, and both are resolved
  during compilation, so the group's full request budget is known before any
  request is sent;
- each iteration binds `{{repeat.index}}` (0-based) and, for `forEach`,
  `{{repeat.item}}`; iterations are independent — a step output bound in one
  iteration is not visible in the next;
- iteration results are recorded under `<step-id>:<index>` (`create-payment:0`,
  `create-payment:1`, …), and the plain step ID resolves to the last completed
  iteration, so assertions may target either;
- the group stops at the first failing iteration and reports its index;
- every iteration of every child counts against the policy request budget.

`forEach` takes a literal list. A list whose length is only known at runtime
is not supported, because an unbounded iteration count cannot be budgeted
before execution.

## Eventually steps

An `eventually` step polls one operation until every `until` condition holds
in the same poll:

```yaml
execute:
  - id: settlement-visible
    type: eventually
    timeout: 10s
    interval: 200ms
    dependsOn: [create-payment]
    steps:
      - id: poll-payment
        type: operation
        operationId: get-payment
        inputs:
          paymentId: "{{steps.created.body.id}}"
    until:
      - assertionType: jsonpath
        params:
          path: status
          equals: settled
        message: settlement completes asynchronously
```

| Field | Required | Description |
|---|---:|---|
| `timeout` | yes | Total convergence budget. |
| `interval` | no | Delay between polls; defaults to `1s` and must not exceed `timeout`. |
| `steps` | yes | Exactly one child operation step — the polled request. |
| `until` | yes | One or more conditions, each an `assertionType` with its documented `params` and an optional `message`. |
| `dependsOn` | no | IDs that must complete before the first poll. |

Semantics:

- the block passes as soon as every condition holds in one poll, and fails
  with `EVENTUALLY_TIMEOUT` when the budget is spent, reporting the number of
  polls and the last unsatisfied condition;
- a failed request is an unsatisfied poll, not a failure: the block keeps
  polling until the budget runs out. That is what lets it converge on a
  system that is still catching up;
- conditions use the same providers, parameters, and messages as
  [assertions](#assertions), and their outcomes count towards the run's
  assertion totals;
- the evidence of the final poll is bound to the child step ID, so ordinary
  assertions may target the polled step;
- the poll count is `1 + timeout / interval` and every poll counts against the
  policy request budget; a combination that would need more than 100 polls is
  rejected during compilation with the minimum `interval` that fits;
- each poll is recorded as a `CONDITION_POLLED` journal event, and console and
  HTML reports show the poll count per node;
- the polled step cannot declare `retry` or `expectError` — the budget already
  governs repeated attempts.

## Generated request values

A step may build its inputs from the schemas the catalog declares, instead of
writing them out:

```yaml
- id: create-payment
  type: operation
  operationId: create-payment
  generate:
    fields: [body]
    strategy: valid
    preferExamples: true
  inputs:
    body:
      currency: EUR
```

| Field | Required | Description |
|---|---:|---|
| `fields` | yes | Declared inputs to generate. `body` uses the operation's request schema; any other name uses that parameter's schema. |
| `strategy` | no | `valid` (default), `boundary`, or `invalid`. |
| `preferExamples` | no | When true (default), an `example` in the schema is sent verbatim instead of a generated value. |

Strategies:

| Strategy | What it sends |
|---|---|
| `valid` | A payload the schema accepts, with every declared property present. |
| `boundary` | The smallest accepted payload — required properties only — with constrained values on their limits: `minimum`, `minLength`, `minItems`, the first `enum` member. |
| `invalid` | A valid payload with exactly one constraint broken, for verifying that the target rejects it. The broken constraint is named in the report. The violation is introduced after explicit `inputs` are applied and avoids the fields they pin, so what the report describes is what the target received. |

Semantics:

- **explicit `inputs` are applied over generated values**, merging objects
  field by field, so a scenario can generate a payload and still pin the
  fields its assertions depend on;
- **values are derived from the run seed and the step ID**: re-running with the
  same `--seed` sends the identical payload. A retry and an `eventually` poll
  resend the same payload — inputs are resolved once per step, not per attempt
  — while the iterations of a `repeat` group each get their own, because each
  iteration is its own step;
- each generated input is recorded as an `INPUTS_GENERATED` journal event
  carrying the seed, the schema ID, the strategy, and a **digest** of the
  value. The payload itself is request data and is never written to the
  journal;
- console and HTML reports name the strategy per step, and for `invalid` the
  constraint that was broken.

Properties marked `readOnly: true` are never generated into a request: they
are server-managed, so sending one back would test the wrong thing.

Supported constraints: `type` (object, array, string, integer, number,
boolean, null), `properties`, `required`, `enum`, `const`, `items`,
`minItems`/`maxItems`, `minLength`/`maxLength`, `minimum`/`maximum` and their
exclusive forms, `multipleOf`, `allOf`, `oneOf`/`anyOf`, `$ref` to another
catalog schema, and the `uuid`, `date-time`, `date`, `email`, `uri`, and
`hostname` formats.

Anything else — most importantly `pattern` — is **rejected during plan
compilation**, naming the field:

```text
Cannot generate 'body' from schema PaymentRequest: $.iban: values constrained
by a regular expression cannot be generated; supply this value explicitly with
inputs
```

The remedy is in the message: pin that field through `inputs`, where an
explicit value overrides generation. The generator never sends a payload it
knows the contract rejects.

## Retries

Operation steps in `setup` and `execute` may declare a retry policy:

```yaml
- id: create-payment
  type: operation
  operationId: create-payment
  retry:
    maxAttempts: 5
    backoffMs: 200
    backoffMultiplier: 2
    maxBackoffMs: 1000
```

| Field | Required | Description |
|---|---:|---|
| `maxAttempts` | yes | Total attempts including the first, 1–10. |
| `backoffMs` | no | Base delay before the first retry. |
| `backoffMultiplier` | no | Exponential growth factor, at least 1. |
| `maxBackoffMs` | no | Upper bound applied to each delay; `0` means unbounded. |

Semantics:

- only errors marked retryable are retried (timeouts, connection failures,
  injected `FAULT_*` errors); validation and non-retryable protocol errors
  fail immediately;
- the delay before retry *n* is `backoffMs * backoffMultiplier^(n-1)`,
  multiplied by a deterministic jitter factor in `[0.9, 1.1)` derived from the
  run seed, the step ID, and the attempt number, then capped at
  `maxBackoffMs`. Identical seeded runs produce identical delays;
- every attempt counts against the execution policy's request budget, so
  retries cannot multiply traffic past `maxRequests`;
- each retry is recorded as an `OPERATION_RETRIED` journal event, and console
  and HTML reports show the retry count per node;
- `retry` cannot be combined with `expectError`, and cleanup steps cannot
  retry;
- assertion evidence always comes from the final attempt.

### Retrying an operation that is not idempotent

A retry resends the same request — byte for byte, including any generated
payload. When the operation is not idempotent, that is not a safety net: a
timeout usually means the request arrived and the *answer* was lost, so the
retry creates a second payment, a second order, a second charge.

Faultora does not refuse this, because it is the subject matter rather than a
mistake to be prevented. Retrying a non-idempotent operation under an injected
fault is how you find out whether the target deduplicates — and a scenario that
retries and then asserts one business effect is one of the more valuable ones
you can write.

What follows from that:

- send an idempotency key when the operation supports one, and pin it in
  `with:` rather than generating it per attempt, so every attempt carries the
  same key;
- when the operation has no such key, expect duplicates and assert on them,
  rather than assuming the retry was free;
- read the `OPERATION_RETRIED` events in a failing run before concluding the
  target is broken: the duplicate may be the scenario's own doing, which is
  exactly the finding.

## Wait steps

Wait steps pause local execution without making a network request.

```yaml
- id: short-delay
  type: wait
  timeout: 250ms
  dependsOn:
    - create-payment
```

`timeout` is required and must be greater than zero.

A wait declared in `cleanup` runs in the cleanup phase, not before it. That is
what lets an obligation outlive something it must not be carried out under —
an injected fault window, or a target that is still settling.

## Faults

Fault steps inject bounded failures into Faultora's own outbound requests. The
built-in provider is in-process: it never touches the target system, its
infrastructure, or any traffic other than the requests this run sends. Injected
failures surface as normalized errors whose code starts with `FAULT_`, so
evidence cannot be mistaken for target behavior.

```yaml
faults:
  - id: lose-response
    faultType: http-response-loss
    targetScope: "*"
    duration: 1s
```

| Field | Required | Description |
|---|---:|---|
| `id` | yes | Stable ID, unique across every scenario section. |
| `faultType` | yes | One of the fault types below. |
| `targetScope` | no | Catalog target ID the fault applies to, or `*` (default) for all targets. |
| `duration` | yes | Positive duration of the fault window. |
| `params` | no | Fault-specific parameters. |
| `dependsOn` | no | IDs that must complete before the fault activates. |

In-process fault types (always available):

| Type | Effect | Parameters |
|---|---|---|
| `http-latency` | Delays each matching request before it is sent; the delay is included in the observed duration. | `delayMs` (required, 1–60000) |
| `http-error` | Fails each matching request before it reaches the target (`FAULT_INJECTED_ERROR`, retryable). | none |
| `http-response-loss` | Delivers the request to the target, then discards the response and reports a timeout-category error (`FAULT_RESPONSE_LOSS`). The metadata records the discarded status code. | none |

Network fault types (require `faultora test --toxiproxy-url <admin-url>` and a
running [Toxiproxy](https://github.com/Shopify/toxiproxy) whose proxies sit on
the network path being tested; `targetScope` must name an existing Toxiproxy
proxy, never `*`):

| Type | Toxiproxy toxic | Parameters |
|---|---|---|
| `network-latency` | `latency` | `latencyMs` (required), `jitterMs`, `direction` |
| `network-timeout` | `timeout` | `timeoutMs` (required), `direction` |
| `network-reset` | `reset_peer` | `timeoutMs`, `direction` |
| `network-bandwidth` | `bandwidth` | `rateKbps` (required), `direction` |

`direction` is `downstream` (default) or `upstream`. Toxics are created with
unique `faultora-` names. The rollback guarantee holds while the CLI process
lives (watchdog plus end-of-run sweep); if the JVM itself is killed, remove
leftover `faultora-*` toxics with `toxiproxy-cli`. Point `--target` at the
proxy's listen address — a network fault on a proxy the traffic never crosses
has no observable effect.

Semantics:

- a fault with no `dependsOn` activates before the first `execute` step;
- the fault window starts at activation and ends after `duration`;
- rollback is guaranteed and exactly once: a hard-expiry watchdog fires even if
  the scenario hangs, and every remaining fault is rolled back when the run
  ends;
- steps that should run under the fault must declare `dependsOn` on the fault
  step ID; steps that should run after it can wait out the window with a
  `wait` step;
- requests that fail because of `http-error` or `http-response-loss` should be
  marked with `expectError: true`, otherwise the step failure stops dependents;
- fault windows and the nodes that ran during them appear in the console and
  HTML reports and as `FAULT_INJECTED` / `FAULT_ROLLED_BACK` journal events.
  Attribution states overlap in time, not causation.

## Dependencies and targets

`dependsOn` may reference any known step ID. All referenced steps must complete
successfully before the dependent step can run. Cycles are rejected.

Assertions implicitly depend on their `targetStep`. When `targetStep` is
omitted, Faultora targets the last step in `execute`. Grouping steps
(`parallel`, `repeat`, `eventually`) hold no evidence of their own, so an
assertion must name a child step; targeting the group — including by omitting
`targetStep` when the last `execute` step is a group — is a compilation
error.

```yaml
- id: check-create
  assertionType: status
  targetStep: create-payment
  dependsOn:
    - audit-ready
  params:
    expected: 201
```

## Assertions

Every assertion has this common shape:

```yaml
- id: unique-assertion-id
  assertionType: status
  targetStep: operation-step-id
  params: {}
  dependsOn: []
```

| Field | Required | Description |
|---|---:|---|
| `id` | yes | Stable ID, unique across every scenario section. |
| `assertionType` | yes | `status`, `header`, `schema`, `jsonpath`, `duration`, `event-count`, `event-unique`, `event-correlation`, or `event-sequence`. |
| `params` | yes | Parameters documented for the selected assertion type. |
| `targetStep` | no | Operation evidence to inspect; defaults to the last `execute` step. A grouping step holds no evidence of its own, so name one of its children. |
| `dependsOn` | no | Additional dependencies that must pass first. |
| `message` | no | Reserved; 0.7.2 reports the assertion provider's evaluated message. |
| `metadata` | no | Arbitrary assertion metadata. |

An assertion that cannot be evaluated is treated as a failed node rather than
a silent pass.

### `status`

Checks the HTTP response status.

Exact status:

```yaml
assertionType: status
params:
  expected: 201
```

Inclusive range:

```yaml
assertionType: status
params:
  min: 200
  max: 299
```

Allowed set:

```yaml
assertionType: status
params:
  documented: [200, 201, 204]
```

When several modes are present, `expected` takes precedence, followed by
`min`/`max`, then `documented`.

### `header`

Checks response headers. Header names are matched case-insensitively.

```yaml
assertionType: header
params:
  name: Content-Type
  contains: application/json
```

Available checks:

| Parameter | Meaning |
|---|---|
| `exists: true/false` | Requires the header to be present or absent. |
| `equals: value` | Exact match against the first header value. |
| `contains: text` | Substring match against the first header value. |
| `count: n` | Exact number of values for the header. |

Provide one check per assertion. Evaluation precedence is `exists`, `equals`,
`contains`, then `count`.

### `schema`

Checks the response body against the schema the API description declares for
it — the assertion that catches contract drift: a field that changed type, a
required field that stopped being sent, a value that left its enum.

```yaml
- id: response-matches-its-contract
  assertionType: schema
  targetStep: create-payment
```

| Parameter | Meaning |
|---|---|
| `status` | Which declared response to check against. Required only when the operation declares more than one. |

The schema is resolved during plan compilation, with every `$ref` expanded, so
the assertion carries a self-contained contract and never reaches back into the
catalog mid-run. Consequences:

- an operation that declares no response schema, or a `status` the operation
  does not describe, is a compilation error naming what is missing;
- when an operation declares several responses, the assertion must say which
  one it means — guessing would check a created resource against the error
  shape;
- a body that was not captured, or that is not JSON, makes the assertion
  **indeterminate**, which fails the node rather than passing it.

Validation covers the same constructs as
[generation](#generated-request-values), plus `additionalProperties: false`,
which fails a response carrying a field the contract does not declare, and
`nullable: true`, which permits an explicit null.

### `jsonpath`

Despite the compatibility name `jsonpath`, expressions use
[JMESPath](https://jmespath.org/) syntax.

```yaml
assertionType: jsonpath
params:
  path: payment.id
  exists: true
```

Available checks:

| Parameter | Meaning |
|---|---|
| `exists: true/false` | Checks whether the expression returns a non-null value. |
| `equals: value` | Compares the selected JSON value with the YAML value. |
| `count: n` | Requires the selected value to be an array of exactly `n` elements. |
| `type: value` | Requires `object`, `array`, `string`, `number`, or `boolean`. |
| `unique: true/false` | Checks whether all values in the selected array are unique. |

Provide one check per assertion. Evaluation precedence is `exists`, `equals`,
`count`, `type`, then `unique`.

Examples:

```yaml
- id: payment-id
  assertionType: jsonpath
  targetStep: create-payment
  params:
    path: id
    type: string

- id: two-payments
  assertionType: jsonpath
  targetStep: list-payments
  params:
    path: "@"
    count: 2

- id: unique-payment-ids
  assertionType: jsonpath
  targetStep: list-payments
  params:
    path: "[].id"
    unique: true
```

### `duration`

Checks the measured operation duration in milliseconds.

Maximum:

```yaml
assertionType: duration
params:
  max: 1000
```

Minimum:

```yaml
assertionType: duration
params:
  min: 50
```

Inclusive range:

```yaml
assertionType: duration
params:
  min: 50
  max: 1000
```

### `event-count`

How many of the messages an observation selected there were. Needs at least one
of `equals`, `min`, or `max`.

```yaml
assertionType: event-count
params:
  equals: 1
```

`min: 1` inside an [eventually block](#eventually-steps) is how "the event
eventually appears" is written — the polling already exists, and an appearance
is a count that stops being zero.

A step that observed no messages *at all*, such as an HTTP step, makes this
indeterminate rather than passing: zero-because-nothing-was-checked must never
read as zero-as-expected.

### `event-unique`

That no two observed messages carry the same value. `by` names where the value
lives: `key`, `header:<name>`, `payload:<dotted.path>`, or a bare dotted path,
which means a payload field.

```yaml
assertionType: event-unique
params:
  by: payload:paymentId
```

This is the duplicate-delivery assertion. A broker that delivers at least once
may deliver a command twice; a correct consumer still emits one event per
payment.

### `event-correlation`

That every observed message belongs to the same exchange. With `equals`, every
message must carry that value; without it, the messages must merely agree with
each other — which is the form to use when the value was generated during the
run.

```yaml
assertionType: event-correlation
params:
  by: header:correlation-id
```

Correlation is what makes a cross-component claim mean anything: an event that
appears after a command is evidence of *that* command only if it carries the
same correlation value.

### `event-sequence`

That the observed messages tell the expected story. Each entry of `of` is a set
of locator/value pairs one message must satisfy, and one message answers for at
most one entry.

```yaml
assertionType: event-sequence
params:
  ordered: true          # the default
  of:
    - { payload:status: accepted }
    - { payload:status: settled }
```

Ordered means the matching messages appear in the observed order, not that they
are adjacent: a workflow may emit events the scenario is not asserting about,
and demanding adjacency would break the assertion every time the system does
something additional and correct.

## Event operations

An operation whose catalog entry declares `protocol: kafka` publishes or
observes messages. There is no separate step type: it is an ordinary
`operation` step, so retries, deadlines, dependencies, `eventually`, and
generated payloads all work on it unchanged.

Publishing:

```yaml
- id: send-command
  type: operation
  operationId: settlePayment      # an operation the catalog says the run publishes
  inputs:
    key: "pay-{{run.seed}}"
    headers:
      correlation-id: "pay-{{run.seed}}"
    body:
      paymentId: "pay-{{run.seed}}"
      amount: 2500
```

A publish waits for the broker's acknowledgement, so a scenario can tell an
unacknowledged write from a target that never reacted.

Observing:

```yaml
- id: read-settlements
  type: operation
  operationId: paymentSettled     # an operation the catalog says the run observes
  inputs:
    match:
      payload:
        paymentId: "pay-{{run.seed}}"
    waitMs: 2000
    maxMessages: 10
```

| Input | Required | Description |
|---|---:|---|
| `match` | no | Which messages this step is about: `key`, `headers`, and `payload` clauses, all of which must hold. Without it, every message in the window is selected. |
| `waitMs` | no | How long the window stays open. Defaults to 5000 and is capped by the run's request timeout; when it is shortened, the report says what was asked for and what was waited. `0` reads the batch already there and returns. |
| `maxMessages` | no | How many matching messages are enough; reaching this ends the wait early. Defaults to 10. |
| `from` | no | `beginning` reaches back past the run's own floor into the channel's history. |

Three properties are worth knowing before writing one:

- **An observation reads forward from when the run started**, resolved through
  the broker's record timestamps, with two seconds of tolerance for the fact
  that the timestamp was set by another machine's clock. History older than
  that is never reported; `from: beginning` is the way to ask for it.
- **Selection, not position, is what makes an observation repeatable.** Two
  iterations of a repeat block read the same window; what makes each one see
  only its own messages is the `match` clause, usually on a correlation value
  the step itself published. On a shared channel, an observation without
  `match` counts whatever else was happening.
- **Observing twice is safe and often necessary.** Each observation re-reads
  from the same floor, so a second one sees everything the first did, plus
  whatever arrived since. Asserting "exactly one" on the poll that first saw
  one event would pass before a second could arrive; asserting it on a later,
  wider observation would not.
- **The window closes when its wait is spent**, whether or not the channel has
  gone quiet. A step ends early only when `maxMessages` matching messages have
  arrived; a busy channel cannot extend it.

Assertion `params` are literal: unlike step `inputs`, they are not expression
templates. Select the messages in the step, and assert on what was selected.

## Unsupported execution features

Faultora fails validation or plan compilation instead of silently ignoring:

- retry policies on cleanup steps;
- destructive operations, unless the run is started with
  `--allow-destructive`;
- `retry`, `expectError`, `outputAs`, `inputs`, and `operationId` on a
  grouping step instead of on its children;
- repeat groups whose iteration count is only known at runtime;
- generating values constrained by a regular expression, and shrinking a
  generated payload to a minimal failing case;
- eventually blocks that would need more than 100 polls;
- assertions targeting a grouping step instead of one of its children;
- parallel, repeat, and eventually steps in cleanup, nested groups, and wait
  steps inside groups;
- distributed execution.

See the [roadmap](ROADMAP.md) for planned delivery stages.
