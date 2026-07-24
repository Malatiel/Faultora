# Scenario reference

This page documents the scenario format implemented by Faultora 0.1.1. The
format is versioned independently from the application:

```yaml
apiVersion: faultora.dev/v1alpha1
kind: Scenario
```

Faultora rejects unsupported versions, missing required fields, duplicate step
IDs, unknown references, dependency cycles, and execution features that are not
available in 0.1.1.

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
java -jar faultora-0.1.1.jar validate --scenario scenario.yaml
```

## Top-level fields

| Field | Required | Description |
|---|---:|---|
| `apiVersion` | yes | Must be `faultora.dev/v1alpha1`. |
| `kind` | yes | Must be `Scenario`. |
| `metadata` | yes | Scenario identity and descriptive metadata. |
| `inputs` | no | Input declarations. Parsed in 0.1.1, but the CLI does not yet expose runtime input binding. |
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

Supported declaration types are intended to be `string`, `number`, `boolean`,
and `object`. In 0.1.1 these declarations are descriptive only: the CLI has no
`--input` option and does not apply declared defaults. Do not depend on scenario
inputs or output expressions until runtime binding is introduced.

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
| `type` | no | Defaults to `operation`; may also be `wait`. |
| `operationId` | for operations | Must match an OpenAPI `operationId`. |
| `inputs` | no | Path, query, header, and body values. |
| `dependsOn` | no | IDs that must complete successfully first. |
| `timeout` | no | Positive duration: milliseconds, `ms`, `s`, or `m`. |
| `outputAs` | no | Reserved; output binding is not active in the 0.1.1 CLI. |
| `retry` | no | Reserved. Omit it from 0.1.1 release scenarios; attempts greater than one are rejected. |
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

Only top-level string input values participate in template resolution.
Runtime expression data is not populated by the 0.1.1 CLI, so expressions such
as `{{inputs.currency}}` and `{{steps.create-payment.id}}` should not be used in
release scenarios yet.

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

Fault types:

| Type | Effect | Parameters |
|---|---|---|
| `http-latency` | Delays each matching request before it is sent; the delay is included in the observed duration. | `delayMs` (required, 1–60000) |
| `http-error` | Fails each matching request before it reaches the target (`FAULT_INJECTED_ERROR`, retryable). | none |
| `http-response-loss` | Delivers the request to the target, then discards the response and reports a timeout-category error (`FAULT_RESPONSE_LOSS`). The metadata records the discarded status code. | none |

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
omitted, Faultora targets the last step in `execute`.

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
| `assertionType` | yes | `status`, `header`, `jsonpath`, or `duration`. |
| `params` | yes | Parameters documented for the selected assertion type. |
| `targetStep` | no | Operation evidence to inspect; defaults to the last `execute` step. |
| `dependsOn` | no | Additional dependencies that must pass first. |
| `message` | no | Reserved; 0.1.1 reports the assertion provider's evaluated message. |
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

## Unsupported execution features

Faultora fails validation or plan compilation instead of silently ignoring:

- network-level fault injection (the built-in provider is in-process only);
- retry policies with more than one attempt;
- parallel and repeat blocks;
- runtime scenario input binding;
- step-output binding and cross-step expressions;
- distributed execution.

See the [roadmap](ROADMAP.md) for planned delivery stages.
