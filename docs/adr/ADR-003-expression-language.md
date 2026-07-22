# ADR-003: Expression language

## Status

Accepted

## Context

Faultora scenarios need an expression language for referencing inputs,
environment values, prior-step outputs, and run metadata. The expression
language must be deterministic, side-effect free, and incapable of filesystem,
process, environment, class loading, reflection, or network access (SEC-07).

## Decision

- Use **JMESPath** via the `jackson-jmespath` library as the expression
  language.
- Expressions are evaluated against a JSON tree (Jackson `JsonNode`).
- The evaluator is isolated: it receives only the evaluation context, not
  access to the application environment.
- Unsupported functions fail during compilation where possible.
- Secret-derived values are redacted before appearing in diagnostic output.

## Rejected alternatives

- **SpEL (Spring Expression Language)**: Requires Spring Framework dependency.
  SpEL can access Java classes, call arbitrary methods, and execute code,
  violating SEC-07.
- **MVEL**: Similar security concerns to SpEL. Requires careful sandboxing.
- **Custom expression language**: Higher implementation cost and risk of subtle
  security holes. JMESPath is well-specified and widely used.

## Consequences

- Expressions are limited to JMESPath's built-in functions (no filesystem,
  process, or network access).
- The `jackson-jmespath` library adds a small dependency to
  `faultora-assertions-core`.
- Expression evaluation is deterministic and testable.
- Custom functions cannot be added without modifying the evaluator.
