# ADR-019: What an assertion means, decided before it is frozen

## Status

Accepted

## Context

The companion to ADR-018: two more items from the list of semantics
`docs/RELEASE_PLAN.md` says must be decided before `v1` freezes them, both in
the assertion that reads a response body.

- `equals` compared JSON nodes with `equals()`, so 5 and 5.0 were different
  values and a template — which always resolves to text — never matched a
  number at all.
- The assertion is named `jsonpath`, evaluates JMESPath, and its own
  documentation advertised `matches` and `length`, neither of which existed.

The second is two separate things wearing one hat: a name that describes the
wrong language, and documentation describing code that was never written. Only
the first is a judgement call.

## Decision

- **Numbers compare as decimals, and a string that spells a number is that
  number.** 5 and 5.0 are one value; `equals: "2500"` matches the amount 2500.
  This is the rule `ObservedRows.number` has applied to tabular evidence since
  0.8, and an assertion language with two answers to "is this equal" has one
  answer too many. Anything else compares as text, and objects and lists
  compare deeply.
- **`type` is what tells 5 from `"5"`.** Making `equals` lenient removes the
  only way to check a value's kind, so the answer is that `equals` was never
  the way: `type: number` is, it already existed, and now it is the documented
  answer rather than an accident of which check happens to be stricter.
- **`matches` is implemented rather than removed.** It was documented, it is
  the one check the provider lacked that a reader would look for, and a regex
  over a captured value is ordinary. A pattern that does not compile is
  indeterminate, not failed: an unusable pattern says nothing about the
  response.
- **The name stays `jsonpath`.** It is wrong — the expression is JMESPath and
  has been since the assertion was written — and it is frozen as it is, with
  the documentation saying plainly which language it evaluates. Renaming means
  either two names frozen at `v1` forever or breaking every scenario that
  exists, and the contract stops growing on its own before the freeze. A wart
  with a signpost costs less than either.
- **The documented list is exactly what the code does**: `exists`, `equals`,
  `count`, `type`, `unique`, `matches`. `length` is gone from it — JMESPath's
  own `length()` function is how a scenario asks for that, and it always was.

## Rejected alternatives

- **Renaming to `jmespath`, with `jsonpath` as an alias.** Honest naming, and
  two names in a frozen contract for the rest of the product's life. The alias
  would never be removable, because removing it is the breaking change the
  alias existed to avoid.
- **A hard rename.** Breaks every scenario, including the examples the
  documentation quotes, for a spelling.
- **`equals` staying strict, with a separate `equalsNumber`.** A second
  assertion parameter to express what one already means, and the strict rule
  would still be the one nobody wanted: a template resolves to text, so the
  strict comparison fails for the most common way of writing a scenario.
- **Removing `matches` from the documentation instead of implementing it.**
  Cheaper, and it leaves the reader who wanted a regex with nothing — the check
  is four lines and the documentation had already promised it.

## Consequences

- A scenario asserting `equals: "5"` against the number 5 now passes where it
  used to fail. That is the intended change; the strict comparison was
  reachable only by writing the literal in the same JSON type the response
  used, which a template cannot do.
- `jsonpath` is a permanent misnomer. The documentation says what it evaluates,
  in the reference and in the provider's own javadoc, so the cost is a reader's
  raised eyebrow rather than a failed run.
