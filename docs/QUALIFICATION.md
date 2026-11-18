# Qualification

For release candidates, use the [1.0 qualification record](RELEASE_QUALIFICATION.md)
to pin the source and artifacts and collect independent acceptance evidence.

## Linux qualification

The default build supports local development without Docker or `lsof`. Such
builds report optional skips and do not prove the omitted behavior.

Qualification requires Linux, Java 21, Python 3, a working Docker daemon and
Docker CLI, `lsof`, and Bubblewrap at `/usr/bin/bwrap` with permission to
create Linux namespaces. Kafka and PostgreSQL run in disposable containers;
image pulls, startup and runner image builds must succeed. No repository
secret or external database/broker credentials are required.

Testcontainers 1.21.4 supplies Docker Engine 29 API compatibility on the 1.x
release line. Keep its modules on the shared version in the parent POM;
overriding the Docker API version is not part of the qualification setup.

Run from a clean checkout with no test selection or exclusion overrides:

```sh
python3 -B -m unittest discover -s scripts -p 'test_*.py'
started="$(date +%s)"
./mvnw clean verify -Pstrict-qualification -B
python3 -B scripts/verify_qualification.py --since "$started"
```

Run the report command only after Maven succeeds. Both commands must pass.
The profile refuses non-Linux systems. Missing Docker or `lsof` fails the
required test condition rather than disabling its suite. Container/image
startup errors remain test failures. The report gate requires fresh reports
for all five Docker suites, runner socket isolation, Unix signal shutdown,
the runner's extension sandbox, scenario migration with both outcomes,
opt-in telemetry from the packaged CLI and runner, approved renderer JARs in the
packaged CLI, and startup of the CLI
offline bundle in a separate network namespace.
It also refuses failures, errors and skips in any suite,
including newly added suites. `clean` and the invocation timestamp keep old
reports from substituting for tests omitted from the current invocation.

CI uses the same profile and report gate. Qualification on Windows is not
supported; the existing local Windows exclusion for `RunnerShutdownTest`
must never be used for this Linux gate. Report local skips and exclusions
separately from strict qualification results.

## Offline runner image qualification

Run the image transfer check on a disposable Linux host with Java 21,
Docker, Buildx, and permission to start a privileged test container. Prepare
the matching trusted receiver image while the sender is still connected.

```sh
docker pull docker.io/library/docker:28.5.2-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac
./mvnw -B verify -pl integration-tests -am \
  -Dtest=OfflineRunnerImageCheck -Dsurefire.failIfNoSpecifiedTests=false
```

The check builds the native runner image from the current packaged JAR,
packages it with `offline-bundle.sh`, and sends the archive to a separate
Docker daemon with an empty image store and only a loopback interface. The
recipient verifies the sender's fingerprint and bundle checksums before
loading the image. It compares the platform, filesystem layers, and runtime
settings, then checks the version and runner help with image pulls and
networking disabled. The test removes the receiver container and its
anonymous image-store volume.

This check runs on demand because the receiving daemon needs privileged
container support. The default Linux qualification includes the separate
offline CLI bundle check.

## Extension resource qualification

On a Linux host with Bubblewrap, cgroup v2 and a working systemd user manager
(version 254 or later), run this check from an otherwise idle user session:

```sh
./mvnw -B verify -pl integration-tests -am \
  -Dtest=RunnerExtensionResourceCheck -Dsurefire.failIfNoSpecifiedTests=false
```

It dispatches a synthetic assertion through the packaged runner and reads the
live limits from the host cgroup. It also checks that the scope is removed at
the end of the run. The strict Linux profile can run on hosts and containers
without a systemd user manager, so select this check explicitly when
qualifying the optional resource limits.

## Independent installation check

Ask someone who has not used Faultora to follow the published README and
[installation guide](INSTALLATION.md) for the release under test. Give them a
clean user environment with Java 21 and GitHub CLI, and a synthetic test
service with its OpenAPI document and URL. Start without a checkout or an
existing Faultora installation. Provide the inputs before the run so the
reviewer can work from the published instructions.

1. Download the release archive and `SHA256SUMS` into an empty directory.
   Verify the archive checksum and release workflow attestation before
   extraction. Follow the installation steps for the selected platform.
2. From a separate project directory, check the version, generate a scenario
   with `faultora init`, validate it, and preview it. The version must match
   the release tag, and the preview must make no requests to the service.
3. Execute the generated scenario against the synthetic service. Expect
   exit code 0 and readable results. Confirm the journal and HTML report are
   available in the requested output directory.
4. Repeat version and validation checks with network access disabled after
   the verified archive has been copied in. Supply a local Java runtime and
   the files bundled with the release. Record the runner bundle transfer and
   verification separately using the [air-gap procedure](../deploy/README.md#carrying-a-bundle-across-an-air-gap).

Record the release tag, platform, archive type, expected and actual results,
and any step that required help or a documentation correction. Use synthetic
inputs throughout. A repeatable run in a fresh environment establishes
installation behavior. The onboarding criterion requires a first-time reviewer
to complete the documented path without source changes.

## Scenario preparation and execution

Measure preparation from task receipt to a runnable plan. Record revisions,
clarifications, approvals and elapsed time. Freeze the scenario, contract,
seed and resolved settings for execution comparisons. Use engine outcomes and
specific assertion results to assess the explanation.

### Controlled execution checks

The [payment fixture](../examples/payment-service/qualification/README.md)
compares a healthy service with each of three hidden defects in both orders.
Each run uses the same prepared inputs and starts with a clean store. The
fixture checks the exact failing assertion IDs and that removing those
assertions hides the defect.

The [catalog fixture](../examples/catalog-service/README.md) checks that
validation and preview call the target zero times. Its normal and defective
variants yield different business assertion outcomes with the same HTTP
statuses and a successful cleanup in both cases.

From the repository root, run the focused checks against the packaged CLI.

```sh
./mvnw -B -pl integration-tests -am \
  -Dtest=PairedQualificationE2ETest,CatalogReferenceE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```
