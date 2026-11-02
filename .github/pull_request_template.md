## Problem and resulting behavior

Describe the concrete trigger, expected outcome and why this approach fits.
Link the issue if there is one.

## Scope and compatibility

Describe effects on scenario syntax, result schemas, extension interfaces,
security boundaries and existing users, or state that none are expected.
Mention documentation and changelog changes where appropriate.

## Checks

List commands actually run and their results. Include test counts and exact
skip counts/reasons or platform exclusions. State what is still unverified;
do not repeat full qualification for every review edit. See CONTRIBUTING.md
and docs/QUALIFICATION.md for affected checks and the final Linux gate.

For a bug fix, describe the regression test; for a new capability, include its
check through the packaged artifact against a running synthetic target.

- [ ] I reviewed the complete diff for unrelated changes, secrets, private
      data, raw run artifacts and machine-specific files.
- [ ] I added or updated the relevant tests and documentation, or explained
      why they are not needed.
- [ ] I followed CONTRIBUTING.md and CODE_OF_CONDUCT.md.
