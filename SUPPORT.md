# Support and version policy

## Where to ask

Use [GitHub Issues](https://github.com/Malatiel/Faultora/issues) for usage
questions, reproducible bugs and feature requests. Search existing issues
first. Include what you tried, the version and runtime, and the expected and
actual result. The [installation guide](docs/INSTALLATION.md),
[examples](examples/README.md), [scenario reference](docs/SCENARIO_REFERENCE.md)
and [troubleshooting guide](docs/TROUBLESHOOTING.md) cover common first steps.
For development, see [CONTRIBUTING.md](CONTRIBUTING.md).

Support is best effort. There is no guaranteed response time, resolution time
or maintenance period. An issue or proposal does not commit the project to a
release date or a new feature.

## Versions and compatibility

The [release plan](docs/RELEASE_PLAN.md) defines the release boundaries:

- Security fixes are provided for the latest published release, as stated in
  [SECURITY.md](SECURITY.md). Older versions have no promised backport stream;
  keep a verified rollback copy and plan upgrades using the
  [upgrade/rollback runbook](docs/UPGRADE_ROLLBACK.md).
- Faultora is currently before 1.0. The scenario API freeze and other existing
  contracts still apply; this does not mean every planned 1.0 gate is met.
  See the release plan, [scenario API decision](docs/adr/ADR-022-freezing-the-scenario-api.md)
  and [output contract](docs/OUTPUT_CONTRACT.md).
- After 1.0, the 1.x line takes defect fixes, security updates, dependency
  updates and documentation. New capabilities or changes to what a scenario
  may say, a report contains or an extension implements belong before 1.0 or
  in 2.0. This policy does not promise a release date or long-term support.

Try to reproduce a bug on the latest published release where practical, and
state the affected version even when upgrading is not possible. Planned work
in the roadmap is not a statement that the current release implements it.

## Evidence for a bug report

Use a small synthetic reproduction. Do not attach a whole run directory, raw
`events.ndjson`, payloads, resolved configuration, environment files or secrets.
With a CLI that provides `diagnose`, create a reduced bundle:

```sh
faultora diagnose --run results --output faultora-diagnostics.zip
```

Here `faultora` is the archive's launcher and `results` the directory the
run's `--output` named; the equivalent is
`java -jar <path-to-faultora.jar> diagnose --run results --output faultora-diagnostics.zip`.
See [diagnostic bundle details](docs/TROUBLESHOOTING.md#reporting-a-problem).
The bundle withholds free text and unknown fields but retains identifiers,
counts, outcomes and digests. Inspect its contents before choosing to attach
it publicly; it is not a promise of anonymity. Nothing is uploaded by this
command.

If the failure happened before a run existed, the installed release lacks
`diagnose`, or bundle creation is refused, state that and give a sanitized
error code and reproduction instead. Do not work around a refusal by
uploading the original journal. Sharing a bundle is optional.

## Security and conduct

Report a vulnerability through the private GitHub advisory flow in
[SECURITY.md](SECURITY.md), rather than a public bug report. For community
behavior and reporting options, see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
