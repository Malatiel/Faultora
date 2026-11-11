# Qualification

## Linux qualification

The default build supports local development without Docker or `lsof`. Such
builds report optional skips and do not prove the omitted behavior.

Qualification requires Linux, Java 21, Python 3, a working Docker daemon and
Docker CLI, and `lsof`. Kafka and PostgreSQL run in disposable containers;
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
for all five Docker suites (currently 23 cases), runner socket isolation and
Unix signal shutdown. It also refuses failures, errors and skips in any suite,
including newly added suites. `clean` and the invocation timestamp keep old
reports from substituting for tests omitted from the current invocation.

CI uses the same profile and report gate. Qualification on Windows is not
supported; the existing local Windows exclusion for `RunnerShutdownTest`
must never be used for this Linux gate. Report local skips and exclusions
separately from strict qualification results.

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
