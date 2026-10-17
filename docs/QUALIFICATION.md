# Linux qualification

The default build supports local development without Docker or `lsof`. Such
builds report optional skips and do not prove the omitted behavior.

Qualification requires Linux, Java 21, Python 3, a working Docker daemon and
Docker CLI, and `lsof`. Kafka and PostgreSQL run in disposable containers;
image pulls, startup and runner image builds must succeed. No repository
secret or external database/broker credentials are required.

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
