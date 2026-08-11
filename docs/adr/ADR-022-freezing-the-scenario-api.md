# ADR-022: Freezing `faultora.dev/v1`

## Status

Accepted

## Context

M6-01 asks for four things: resolve `v1alpha1` feedback, publish migration
tooling and a compatibility matrix, freeze `faultora.dev/v1`, and add
deprecation diagnostics for future evolution.

The semantics a freeze would otherwise have inherited by accident were decided
first — five of them in 0.9, each with an ADR that says what was rejected
(ADR-016, ADR-018, ADR-019). A freeze applied before those decisions would have
frozen whatever the code happened to do, including the parts that were never a
choice. That work is done, which is what makes this decision available now.

## Decision

- **`faultora.dev/v1` freezes the semantics `v1alpha1` already had.** No field
  is renamed, removed, or given a new meaning. A document moves by changing one
  token, and the migrator says so rather than implying a transformation it does
  not perform. The alternative — using the freeze as an opportunity to tidy the
  format — would have made every existing scenario a rewrite, on the release
  whose purpose is that scenarios stop being rewritten.
- **What "frozen" permits, exactly.** Within 1.x: an addition an earlier 1.x
  release would have *ignored* rather than refused. Everything else — a rename,
  a removal, a type change, a field that becomes required, an existing field
  that starts meaning something else — is a new `apiVersion`. Stated as a rule
  because "no breaking changes" is a phrase everyone agrees with and nobody can
  apply to a specific diff.
- **The freeze is a test, not a sentence.** The shape of the parsed document is
  derived from the model and compared with a committed list; a build that
  changes the surface fails until the change is written down. This is the
  project's standing gate applied to the freeze itself — *a guarantee without a
  failing test is a wish* — and the reason it is not merely documented is that
  the 0.5 review found three accepted ADR sentences the code had quietly
  drifted away from.

  What the snapshot cannot hold is behaviour: two releases can accept identical
  documents and mean different things by them. That is what the semantics ADRs
  and their own tests are for, and the snapshot's javadoc says so rather than
  letting it look like more than it is.
- **`v1alpha1` is still read, and the warning says until when.** Refusing it on
  the release whose purpose is stability would break every scenario written
  against the preview. It parses, it runs, and it produces a **warning** —
  never an error, because a deprecation that changed an exit code would turn
  every existing pipeline red the day somebody upgraded. The diagnostic names
  the command that fixes it and the release that stops reading it: a
  deprecation without a date is a warning people learn to scroll past.
- **The sunset is 2.0**, not a 1.x release. The version that stops reading a
  document format is a major one, or "frozen" means nothing.
- **One place knows about versions.** What a document may declare, what `init`
  writes, what the migrator produces, and what the documentation promises are
  the same fact four times; `ApiVersions` is where it is stated. Two of them
  written separately agree on the day they are written — the digest algorithms
  and the evidence policy both proved that during 0.9.
- **The migrator reports by default and writes when asked**, and edits text
  rather than round-tripping YAML. A parser round trip returns a correct
  document with the comments dropped, the key order changed and the block
  scalars reflowed — a diff nobody can review, for a change of one word. It
  writes atomically, because a tool run over somebody's whole repository must
  not be able to leave a truncated scenario behind.

## Rejected alternatives

- **Refusing `v1alpha1` at 1.0.** The cleanest possible contract and the worst
  possible upgrade. Everything written against the preview would stop working
  on the release that promises it will not.
- **Making the deprecation an error behind a flag.** Two behaviours to keep
  true, and the flag would be discovered by whoever least wanted it.
- **Renaming the `jsonpath` assertion while the format was open.** Reviewed in
  ADR-019 and rejected there: it means either two names frozen in the contract
  forever or breaking every scenario that exists. The documentation says which
  language it evaluates, which is the half of the problem worth fixing.
- **A snapshot of the YAML grammar rather than of the model.** Closer to what a
  user writes, and it would have to be maintained by hand — a snapshot nobody
  regenerates is a snapshot that stops being checked.

## What this does not cover

- **The observation catalog's `apiVersion` is not validated by anything.** It
  declares one, and no importer reads it, so a catalog claiming
  `faultora.dev/v99` is accepted today. The migrator moves those documents for
  consistency, and that is tidiness rather than compatibility. Freezing that
  document's surface — with its own snapshot, and validation that refuses a
  version it does not know — belongs with the same treatment applied to
  AsyncAPI and observation imports, not smuggled in beside the scenario freeze.
- **The internal step model is still an open hierarchy.** `RELEASE_PLAN.md`
  names 1.0 as the moment to seal it, on the argument that reshaping the parsed
  document costs nothing extra then. That is a change to internal
  representation and not to the document surface, so it is a separate slice of
  the same milestone; doing it inside this one would have mixed a contract
  decision with a refactor and made both harder to review.
- **The runner protocol.** ADR-020 says 1.0 freezes it and `ProtocolVersion`
  already negotiates. It is not part of "stable scenario API" and freezing it
  needs its own decision, not a side effect of this one.

## Consequences

- Every document in this repository now declares `faultora.dev/v1`, migrated by
  the tool rather than by hand — which is also the tool's end-to-end proof,
  since the suites that read those documents still pass over them.
- A field added to the scenario model fails the build. That is the intended
  cost: the failure is where the decision gets made, rather than in a release
  note written afterwards.
- Two versions are read for a whole major release, so the parser carries a
  branch it did not have. The branch is three lines and one constant, which is
  the cheap end of what compatibility usually costs.
