# ADR-016: Assertion parameters are expressions

## Status

Accepted

## Context

Roadmap M3-04 asks for "compound invariants across HTTP, event, and database
evidence" — the claim the whole product rests on. An invariant that spans
components is, concretely, a comparison between values two different steps
produced: the amount the API accepted equals the amount the ledger recorded,
and the event that followed carries the same identifier.

Faultora could not express that. Step `inputs` resolve `{{...}}` templates
against a context that already holds every bound step's evidence; assertion
`params` were the one place in the scenario language that did not. So an
assertion could only compare against a literal, and 0.7 had to write the
limitation down — the reference page said "assertion `params` are literal" and
the event scenario had to select messages by a business identifier because
`event-correlation` could not be told to expect a scenario input.

The obvious reading of M3-04 is a compound assertion type. That reading is
wrong, and noticing why is the whole of this decision.

## Decision

- **Assertion parameters resolve as expressions, exactly as step inputs do** —
  nested maps and lists included, a lone template keeping its type. Every
  assertion that exists becomes able to compare against any bound step's
  evidence, and every assertion added later inherits it.
- **There is no compound assertion type, and there will not be one.** A
  dedicated provider would have to re-implement comparison for each existing
  assertion or invent a comparison language of its own. One mechanism that
  composes with `status`, `jsonpath`, `duration`, and the event assertions is
  worth more than a construct beside them.
- **A parameter that reads a step makes that step a dependency.** The compiler
  extracts every reference — one expression may name two steps, and ordering
  after only the last of them is ordering after neither — and adds them all, so
  the values are bound before the comparison happens. Without this an assertion
  could be ordered before the step it reads from and compare against nothing,
  and pass. References are looked for inside `{{...}}` only, so the words
  "steps." in a message invent no dependency.
- **A reference to a name no step binds fails plan compilation**, naming the
  `outputAs` that is missing. This is a scenario mistake, and mistakes about
  what a scenario is comparing belong before the run.
- **A template that resolves to nothing fails the assertion by name**, at any
  depth. A parameter written as a literal may legitimately be absent; one
  written as an expression that resolved to nothing cannot be. Comparing against
  null would let the provider decide what null means, and the answer would not
  be the author's.
- **A parameter reading a secret is refused.** An assertion compares by writing
  what it compared into its message, and that message reaches the journal, the
  console, and the HTML report. There is no way to compare against a secret and
  keep it one, so the assertion says which parameter reads a secret and stops —
  it never names the value. This is the only new path by which a secret could
  have reached a report, and it was opened by this decision, so it is closed by
  it.
- **The `until` conditions of a polling block get exactly the same treatment**,
  including the compile-time dependencies. A condition is an assertion that runs
  repeatedly, and the flagship use of expression parameters is a condition —
  wait until the record the API returned appears somewhere else. A guard the
  assertion section had and the polling block did not would be worse than no
  guard at all, because it would look present.
- **The schema assertion's `params.status` stays literal.** It selects which
  declared response schema to check, and that selection happens when the plan is
  built — a template there is refused with a diagnostic rather than looked up as
  a schema named `{{...}}`.
- **An eventually block's `until` conditions resolve once, beside the polled
  step's inputs.** Every poll asks the identical question; resolving per poll
  would add a second place where a poll's meaning could drift.

## Rejected alternatives

- **A `compound` assertion type** taking several `targetStep`s. It needs its
  own comparison vocabulary, and every existing assertion stays unable to reach
  a second step.
- **A list-valued `targetStep`.** It answers "which evidence" but not "compared
  against what", which is the actual question.
- **Leaving parameters literal and adding a `precondition` step** that fails
  when two values differ. That is an assertion wearing a step's clothes, and it
  would not appear in the assertion counts a report is read for.

## Consequences

- A parameter value containing `{{` now resolves where it previously did not.
  No published scenario or example relied on the literal reading; two spec
  fixtures did, and they were expressing something that had never worked.
- An assertion can depend on a step in a *later* section only if that section
  compiles before assertions do. Cleanup compiles after, so an assertion cannot
  read a cleanup step's output — which is correct: an assertion about cleanup
  would run before the cleanup it describes.
- Comparing against a secret is not possible, by design. `[REDACTED]` exists for
  diagnostics, not for assertion messages: a provider builds its own message out
  of the values it was given, so redaction after the fact would have to be
  implemented by every provider and would be wrong in whichever one forgot.
  Refusing at the boundary needs no provider to cooperate.
- This settles one of the semantics the release plan listed as "decided before
  they are frozen". The remaining ones — list indexing in dotted paths, an
  assertion named `jsonpath` that evaluates JMESPath, `equals` distinguishing 5
  from 5.0 — are untouched and still owed before 1.0.
