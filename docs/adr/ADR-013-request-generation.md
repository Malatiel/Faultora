# ADR-013: Request generation from catalog schemas

## Status

Accepted

## Context

Roadmap M2-02 asks for values generated from JSON Schema constraints, examples
preferred where configured, deterministic boundary and invalid strategies, and
recorded seeds. Writing every payload by hand is the main reason scenarios go
stale: a field added to a contract is silently absent from every request until
someone notices.

Two constraints shape the design. Generated traffic still hits a real system,
so it must be reproducible and bounded like everything else Faultora sends.
And JSON Schema is far larger than any generator can honour: pretending
otherwise produces payloads the target rejects for reasons that have nothing
to do with the behaviour under test.

## Decision

- **A `generate` block on the step, not a function in expressions.** Values are
  requested declaratively (`generate: {fields: [body], strategy: valid}`).
  ADR-003 keeps the expression language read-only and side-effect free;
  a `{{random.integer()}}` would make every expression a potential source of
  irreproducible traffic.
- **An authored example is preferred but not trusted.** When `preferExamples`
  is on, an `example` declared in the schema is sent verbatim only if it
  satisfies that schema; a stale example — common in real documents — falls
  through to generation rather than producing a request the contract rejects.
- **`readOnly` properties are never generated.** OpenAPI declares them
  server-managed; a request carrying one tests the target's tolerance for
  echoed state rather than the behaviour under study.
- **A violation is introduced after explicit inputs are applied**, and avoids
  the properties they pin. Breaking a constraint first lets the pinned values
  put it back, leaving a valid payload while the report claims a violation —
  a negative test that silently became a positive one.
- **Explicit inputs are applied over generated ones**, merging objects field by
  field. A scenario generates a whole payload and still pins what it asserts
  on, which is what keeps generated requests compatible with meaningful
  assertions.
- **Determinism from the run seed and the node ID.** A retry and a poll resend
  the identical payload, because inputs are resolved once per node execution
  rather than per attempt — otherwise a retry would quietly test a different
  request, and the idempotency scenarios this tool exists to run would prove
  nothing. Repeat iterations differ, because each iteration is its own node.
- **Unsatisfiable constructs fail compilation, naming the field.** A regular
  expression, an empty numeric range, or a missing schema stops the run before
  the first request, with the remedy in the message: supply that value
  explicitly. The generator never emits a value it knows the contract rejects.
- **The journal records the seed and a digest, never the payload.** A generated
  body is request data, and the evidence policy — not the generator — decides
  whether request data is kept. Seed, schema ID, and strategy are what a replay
  needs.
- **Own module (`faultora-schema`), not the engine.** Generation and validation
  are catalog-level capabilities; the response-schema assertion of M1-06 will
  need the same validator without depending on the engine.

## Rejected alternatives

- **A full JSON Schema implementation** (or a third-party validator
  dependency): the supported subset is documented and enforced; silently
  ignoring `pattern` or `allOf` would produce payloads rejected for reasons
  unrelated to the test.
- **Generating at compile time and storing payloads in the plan**: plans stay
  reviewable, but every repeat iteration would send an identical body, and the
  plan would carry request data that the evidence policy governs.
- **Random values from a clock-seeded generator**: a failing run could not be
  reproduced, which defeats the purpose of recording a seed at all.
- **Shrinking failed payloads to a minimal counterexample**: valuable, and out
  of scope here. It belongs with property-based testing across distributed
  runs, which the roadmap places after 1.0. What ships now is the recorded
  seed that makes a failure reproducible by hand.

## Consequences

- A scenario that generates its body follows the contract as the contract
  evolves: a new required field appears in requests without editing the
  scenario.
- The generator's supported subset is a documented contract of its own; a
  schema outside it is rejected loudly rather than approximated.
- `SchemaValidator` exists alongside the generator and is what proves generated
  payloads satisfy their source schema — the milestone's acceptance criterion —
  rather than the generator vouching for itself. It is also used in production,
  to decide whether an authored example can be trusted.
- The compile-time feasibility check generates with the run seed, while the run
  derives a per-step seed, so a schema with alternatives can pass compilation
  and still fail at execution. Such a failure is contained: it fails its node
  or group, leaving cleanup and the run's terminal event intact.
- Inline request-body schemas had to be captured by the OpenAPI importer first:
  before this, an operation whose body was written inline looked as if it took
  no structured input at all.
