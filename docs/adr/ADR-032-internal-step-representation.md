# ADR-032: Keep document records and lower directly to sealed plan nodes

## Status

Accepted. No runtime or document-surface change.

## Context

[ADR-022](ADR-022-freezing-the-scenario-api.md) left the internal step
representation as a separate decision. `ScenarioStep` is already a final
record, not an extensible Java subtype hierarchy. What is open is its textual
`type` discriminator and the combinations of optional fields a parsed
document can carry. Calling that record sealed would not close those
combinations or improve execution exhaustiveness.

The existing compiler already lowers document data to the sealed `PlanNode`
interface. A third, intermediate sealed representation would sit between
these two models. Its value has to exceed the cost of another conversion and
another place to keep defaults, groups and diagnostics consistent.

## Current call sites

Paths below are relative to the named module's `src/main/java/dev/faultora/`.

| Call site | Representation and responsibility |
|---|---|
| `faultora-spec`, `spec/parser/ScenarioParser.parse` | Jackson reads the document into `ScenarioDocument` and its `ScenarioStep` records; keeps source diagnostics |
| `faultora-spec`, `spec/validator/ScenarioValidator.validateSteps` | Normalizes omitted `type` to operation for validation; rejects unknown types and misplaced fields, validates duration/retry/group rules |
| `faultora-spec`, `spec/validator/ScenarioValidator.validateGroupStep`, `validateRepeatStep`, `validateEventuallyStep` | Reports invalid group shapes and bounded iteration/polling requirements in document terms |
| `faultora-runtime`, `runtime/RunPreparation.prepare` | Parses, validates and compiles once before producing prepared execution artifacts |
| `faultora-engine`, `engine/plan/PlanCompiler.compileSteps` | Selects operation/wait/parallel/repeat/eventually and lowers them, preserving step IDs and phase rules |
| `faultora-engine`, `engine/plan/PlanCompiler.compileOperation`, `compileGroupChildren`, `compileRepeat`, `compileEventually` | Resolves catalog operations, generated inputs, dependencies, policies and finite budgets into typed nodes |
| `faultora-engine`, `engine/plan/PlanNode` | Already seals the nine executable node kinds, including assertion/fault/cleanup nodes that are not ScenarioStep variants |
| `faultora-engine`, `engine/LocalEngine` | Executes typed nodes; its node dispatch and group execution use `PlanNode`, not textual `ScenarioStep.type` |

`GeneratedInputCompiler.compile` also reads `ScenarioStep.generate` while
lowering against schemas. Other compiler passes such as dependency/group
indexing still inspect document IDs and children. None requires a third
runtime step model.

The two validation stages are intentional. Document validation explains
invalid combinations in terms the author can fix. Compilation additionally
needs resolved catalog operations and effective policy. A sealed wrapper
cannot make the latter known at parse time.

## Decision

Keep `ScenarioStep` as the document DTO, including its current record
components and nullable/default behavior. Keep direct lowering into sealed
`PlanNode`; do not add an `InternalStep` hierarchy now. Runtime consumers
continue to use typed plan nodes rather than rechecking textual kinds.

This preserves the `faultora.dev/v1` shape and behavior. Do not replace the
`ScenarioStep` record with Jackson-polymorphic subtypes, change its fields or
adjust the committed `ScenarioSurfaceTest` snapshot as part of this decision.
Unknown textual kinds must still produce diagnostics, and an omitted type
must still mean an operation. The existing group, cleanup, policy and budget
tests remain the semantic gates; a surface snapshot alone cannot prove them.

An extra sealed document-normalization layer becomes justified only if a
second independent consumer needs normalized document steps before catalog
resolution, or concrete defects show repeated kind/default logic escaping
the parser/compiler boundary. Record those call sites and failing behavior
before choosing that refactor. Current code does not supply that evidence.

## Alternatives

| Alternative | Benefit | Cost / reason not selected |
|---|---|---|
| Replace the public record with a sealed hierarchy | Java can express kind-specific fields | Changes Jackson/document mapping and the frozen reflection surface; invalid documents still need positioned diagnostics |
| Add internal sealed operation/wait/group records | Moves structural field checks into constructors after normalization | Introduces a third model and conversion beside the already sealed plan; policy/catalog validation remains in the compiler |
| Seal execution nodes | Exhaustive executable variants | Already implemented by `PlanNode`; no new refactor is needed to obtain this property |
| Keep the DTO-to-plan boundary | One document representation and one executable representation | Kind checks stay localized in validator/compiler; adding a kind still requires their coordinated tests |

## Consequences and validation

No production code changes or new wrappers are needed. The compiler still
must defend its own public entry point; callers must not be able to obtain
an executable invalid step just by bypassing `ScenarioValidator`.

The unchanged `ScenarioSurfaceTest` is run as a narrow check of the preserved
contract. Its three cases cover the surface snapshot, sensitivity to a lost
field and version/deprecation declarations; they do not claim new execution
coverage. The existing compiler and executor tests remain untouched.

If a future normalization refactor is accepted, separate it by operation,
wait, parallel, repeat and eventually lowering, with before/after diagnostics,
compiled-plan and request-budget equivalence for each. Assertion/fault/cleanup
node contracts must not be forced into document step variants merely to make
the new hierarchy look uniform. That future implementation requires its own
approved work; this ADR ends at the representation decision.
