# Contributing to Faultora

Bug reports, documentation fixes, tests and code changes are welcome. Read the
[Code of Conduct](CODE_OF_CONDUCT.md) and [support policy](SUPPORT.md) first.
Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Set up a source checkout

Clone the repository and run these commands from its root. Install JDK 21 and
set `JAVA_HOME` to that JDK. The included Maven Wrapper selects Maven; no
separate Maven installation is required. Its first invocation downloads Maven
and build dependencies, so a fresh checkout needs network access.

On Linux or macOS:

```sh
./mvnw -version
./mvnw -pl faultora-cli -am -DskipTests package -B
./mvnw -pl faultora-model -am test -B
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -pl faultora-cli -am -DskipTests package -B
.\mvnw.cmd -pl faultora-model -am test -B
```

The package command builds the CLI and its dependencies without running tests.
The last command runs a small model test suite without Docker; it is a setup
check, not qualification of the whole project. The executable CLI is
`faultora-cli/target/faultora-<version>.jar`, where `<version>` is `revision` in
the root POM. Run it with `java -jar <path-to-jar> --version`.

For a first target run, follow [installation](docs/INSTALLATION.md),
[examples](examples/README.md) and [troubleshooting](docs/TROUBLESHOOTING.md).
Use disposable targets and synthetic data when developing tests.

## Choose the scope

Search existing issues and open a small issue for a substantial change before
implementing it. Describe the user problem, expected behavior and a way to
check it. Small corrections can go straight to a pull request.

Start with the [architecture](docs/ARCHITECTURE.md) and relevant
[architecture decisions](docs/adr/README.md). Keep model and SPI independent
of implementation modules, and keep protocol-specific work in connectors and
importers. Some architecture and roadmap sections describe planned work;
check the [release plan](docs/RELEASE_PLAN.md) and current behavior before
making a capability claim.

Scenario syntax, output schemas and extension interfaces have compatibility
constraints. Explain any effect on them in the pull request. Under the release
plan, the future 1.x line accepts fixes, security/dependency updates and
documentation; new capabilities or contract changes belong before 1.0 or in
2.0. See [SUPPORT.md](SUPPORT.md) for version policy.

## Test the change

Add a regression test for a bug, and cover successful behavior, boundaries and
refusals for a production-code change. Prefer deterministic tests of public
behavior over sleeps or tests that repeat implementation details. Use
synthetic fixtures without credentials or private addresses.

During development run the affected modules, including upstream dependencies:

```sh
./mvnw -pl faultora-model -am test -B
```

To select one test, for example:

```sh
./mvnw -pl faultora-model -am -Dtest=ModelRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false test -B
```

Replace the module and test name with the ones your change affects. For CLI or
integration changes, build the packaged CLI first and run the relevant E2E
against it. A new capability needs a check through the packaged artifact,
not only a unit test.

Before submitting, run the formatter check and inspect the diff:

```sh
./mvnw spotless:check -B
git diff --check
```

Use `spotless:apply` to fix Java/POM whitespace, then review its changes.
Spotless does not validate Markdown or issue templates; check their links and
preview them separately. On Windows replace `./mvnw` with `.\mvnw.cmd` in the
commands above.

Report the commands, results, and exact counts and reasons for skipped tests
or platform exclusions in the pull request. Docker tests need a working
Docker daemon and may pull images; skipped tests do not establish their
guarantees. The final combined candidate receives one full qualification run,
rather than repeating the whole suite for every edit. CI runs the strict
Linux profile and report gate described in [QUALIFICATION.md](docs/QUALIFICATION.md);
it requires Java 21, Python 3, Docker and `lsof`. A local Windows run is not a
substitute for that gate.

## Submit a pull request

Keep each change focused and explain the problem, resulting behavior and why
the chosen approach fits. Update the relevant docs and add a user-facing
entry under Unreleased in [CHANGELOG.md](CHANGELOG.md) when appropriate.

Before sharing, inspect all changed files for secrets, target data, raw run
artifacts, machine-specific paths and unrelated generated files. Follow
[SECURITY.md](SECURITY.md) for sensitive reports; CI also scans for secrets.
For bug evidence, use the reviewed diagnostic bundle described in
[SUPPORT.md](SUPPORT.md), not a run directory. Submit contributions under the
project's [Apache License 2.0](LICENSE).
