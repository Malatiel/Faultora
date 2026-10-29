# Upgrading and rolling back

How to move the CLI and a runner from one release to another, and back, and
what does and does not survive the move. It covers the CLI and the runner; a
controller is not part of any release yet.

## Before either

1. **Verify the new release** as [Verifying a download](INSTALLATION.md#verifying-a-download)
   describes: its checksum, and a GitHub attestation made by `release.yml` for
   that tag. `deploy/verify-release.py` does both for any release file.
2. **Keep the release you are leaving**, verified the same way, next to the new
   one. A rollback is switching back to a file you already trust, not a
   download made under pressure.
3. **Validate your scenarios with the new release** before switching:
   `faultora validate` and `faultora test --preview` contact no target. A
   scenario that only the newer release accepts will be refused by the older
   one, which matters for a rollback: within `faultora.dev/v1` a newer release
   may add optional fields an older one does not know.

## The CLI

The CLI is a JAR, or an archive with a launcher, and keeps no state between
runs. Install the new version beside the old one — a versioned directory per
archive — and move whatever chooses the command (a `PATH` entry, a symbolic
link, a CI variable such as `FAULTORA_VERSION`) to the new one. Rolling back is
moving it back. Output directories written by either version stay readable:
the journal and JSON report follow the frozen [output contract](OUTPUT_CONTRACT.md).

## A runner

A runner holds a run only while it is executing it, so an upgrade is a drain,
a replacement and a restart.

1. **Drain.** Stop dispatching to this runner if the control plane can, then
   send SIGTERM. The runner stops asking for work and waits up to
   `--shutdown-grace` (30s by default) for the run in flight to finish and
   report. Set the platform's own termination grace at or above that value.
2. **Confirm it is idle** before the process goes:
   `faultora health --file <status file>` adds `running <run id>` after the
   runner's state while a run is in flight; a line without it means idle.
3. **Replace** the JAR or the image tag with the verified new one, keeping the
   same keystore, truststore, policy keys and working directory.
4. **Start and check readiness** with `faultora health --file <status file>
   --require-registered`.

Rolling back is the same four steps with the previous release.

## What does not survive

- **A run cut off by the move is not resumed.** If the grace expires first,
  the run stops and its outcome may never reach the control plane.
  `RunnerShutdownTest` shows both halves: with a grace the outcome arrives,
  with one too short it does not.
- **A journal the runner still holds when it stops is not delivered after
  restart,** by the same version or another. It stays in the working
  directory, and collecting it is a person with shell access rather than the
  protocol. Durable delivery across restarts is designed in
  [ADR-031](adr/ADR-031-runner-recovery-ownership.md) and not implemented.
- **Nothing in the working directory is migrated.** Keep it on storage that
  outlives the container, so that such a journal can still be collected.

## What was checked

`UpgradeRollbackCheck` runs every published payment-service scenario against
a fresh example API on two release JARs a person has already verified, and
requires the same exit code, assertion outcomes and node outcomes from both;
each journal either release wrote must be readable by the current reader, and
both must read the same runner status file. It is not part of the ordinary
build:

```bash
FAULTORA_PREVIOUS_JAR=/path/to/faultora-0.11.2.jar \
FAULTORA_NEXT_JAR=/path/to/faultora-0.11.3.jar \
./mvnw -B verify -pl integration-tests -am \
  -Dtest=UpgradeRollbackCheck -Dsurefire.failIfNoSpecifiedTests=false
```

Its result for 0.11.2 and 0.11.3, both downloaded from their releases and
verified with `deploy/verify-release.py`, on 2026-10-29: all 12 scenarios gave
the same exit code, assertion outcomes and node outcomes on both; every
journal was readable; both releases read the same runner status file and
reported it ready. That is evidence for this pair of releases, not a promise
for every pair; run it again for the pair you are moving between.
