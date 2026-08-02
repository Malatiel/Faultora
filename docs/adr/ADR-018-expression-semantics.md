# ADR-018: What an expression means, decided before it is frozen

## Status

Accepted

## Context

`faultora.dev/v1` freezes the scenario contract at 1.0, and a freeze inherits
whatever the code happens to do — including the parts that were never a
decision. `docs/RELEASE_PLAN.md` lists them, and three belong to expressions:

- a lone `{{expr}}` resolves to null while the same expression interpolated
  into text yields an empty string;
- any parenthesis anywhere in an expression routes it to JMESPath;
- a dotted path cannot index a list, which is why the first message an
  observation selected is bound *beside* the list rather than reached into.

Each is small. Each becomes permanent on the day `v1` is declared, and each is
the kind of thing that is answered by whatever the implementation did on a
Tuesday unless it is answered on purpose.

One thing had to be built before any of them could be decided: the resolver
returned Java `null` for a path that does not exist **and** for a path holding
JSON `null`. A decision that treats those differently cannot be implemented
against a resolver that cannot tell them apart.

## Decision

- **Missing and null are different answers.** `resolvePath` returns a missing
  node for a path nothing matches and a null node for a path whose value is
  null. Every rule below rests on that distinction, and it is the reason it was
  built first.
- **A missing path is an author's error, in both positions.** A template
  naming something that does not exist fails the step by name, whether it was
  the whole value or part of a sentence. Silently substituting null in one
  place and an empty string in the other is how a scenario passes while
  checking nothing: `"/payments/{{steps.created.body.id}}"` against a missing
  id requested `/payments/` and got a 404 that read like the API's fault.
- **An explicit null is a value.** `{{expr}}` alone yields null, and
  interpolated it yields an empty string. A document that says a field is null
  means it; the run reports it rather than refusing it. This is the one half of
  the old inconsistency that stays, because the two positions genuinely differ
  — one carries a JSON value, the other builds text.
- **An assertion parameter is the exception, and says why.** A template that
  resolves to null is refused there, because an assertion compares against a
  value and null is the absence of one: every provider would otherwise decide
  for itself what comparing to null means, and the answers would differ. The
  refusal names `exists: false` as the question the author was reaching for. A
  parameter written as a literal null is left alone — it says null on purpose,
  and nothing had to resolve for it to.
- **Only a leading function call goes to JMESPath.** An expression is a dotted
  path unless it begins with an identifier followed by `(`. "Contains a
  parenthesis" made `steps."weird(key)".id` into a JMESPath expression and then
  reported a parse error about a scenario that was correct.
- **Inside a function call, a hyphenated name must be quoted**, and the
  diagnostic says so. JMESPath's grammar rejects `type(steps.create-payment.id)`
  — verified, not assumed — while `type(steps."create-payment".id)` is fine.
  Every example scenario names steps with hyphens, so leaving this to be
  discovered through an ANTLR parse dump was the same defect as a documented
  feature that does not exist.
- **A dotted path indexes a list.** A numeric segment on an array selects by
  position, so `steps.read.messages.0.payload.paymentId` reads what the step
  observed. An object is still addressed by key, including a key that looks
  like a number, because an object's key is a name and an array's index is a
  position. Out of range is missing, which the rule above then refuses.
- **`protocol.message` stays.** The engine binds the first observed message
  beside the list, and that binding existed because a path could not index one.
  It stays as a documented convenience rather than being removed the moment the
  workaround stops being necessary: scenarios use it, and it reads better than
  `.0.` at the only place it is used.

## Rejected alternatives

- **Refusing an interpolated null.** It would make the two positions agree by
  making both strict, and it would break every scenario interpolating an
  optional value into text — a much larger change than the inconsistency being
  fixed, and one nothing in the repository could have sized.
- **Refusing a missing path at compile time only.** A reference to an undeclared
  input can be caught there, and is. A path into a step's response body cannot:
  what a response contains is not known until it arrives. The runtime rule is
  the one that covers both, and the compile-time check is kept where it can
  name the error earlier.
- **Dropping JMESPath from expressions.** Two languages in one field is a wart.
  But `type()` and `length()` are used, JMESPath is already a dependency of the
  assertion that needs it, and removing a facility that works is a bigger
  contract change than narrowing when it is reached for.
- **A `jmespath:` prefix to select the language explicitly.** Honest, and one
  more thing frozen at `v1` for a facility whose whole surface is two function
  calls.
- **Bracket indexing (`messages[0]`).** It is what JMESPath and JSONPath both
  use, and it would need a second syntax in the path grammar where a numeric
  segment needs none.

## Consequences

- A scenario that relied on a missing path quietly becoming an empty string now
  fails. That is the intended change, and it is a breaking one: it is being made
  before the freeze rather than after, which is the only reason it can be made
  at all.
- `evaluate` returns a node rather than null for a null value, so a caller
  distinguishing "no such thing" from "the thing is null" can. Both existing
  callers went through `resolveTemplate`, so the change is contained.
- A function over a hyphenated step name needs quotes. The diagnostic says so
  by name; the alternative was a parse error naming a character position.
