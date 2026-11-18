# 1.0 release qualification

Use this record against the [1.0 exit gate](ROADMAP.md#10-exit-gate) and
[release security checks](SECURITY.md#163-release-qualification). Pin the source
commit, application revision, and artifact hashes for each run. Mark each row
`pass`, `fail`, `blocked`, or `pending` and attach the result for that candidate.
Carry findings forward and repeat the affected checks after a change.

The current release workflow publishes directly when a pushed `v*` tag matches
the Maven revision. The release verifier and runner-image publisher accept
stable tags. Qualify the unpublished candidate using its source commit and
artifact hashes. A pre-tag review can close source and candidate checks. The
published artifact checks need the stable release and its runner image.

## Unpublished candidate

Use an isolated Linux checkout with the prerequisites in
[Linux qualification](QUALIFICATION.md#linux-qualification). Take the full
candidate commit from the maintainer and set `FAULTORA_CANDIDATE_COMMIT`. Run
from the candidate checkout. Capture the commands and their exit status.

```sh
set -eu
: "${FAULTORA_CANDIDATE_COMMIT:?Set the full candidate commit}"
test "$(git rev-parse HEAD)" = "$FAULTORA_CANDIDATE_COMMIT"
test -z "$(git status --porcelain)"
version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
printf 'Candidate commit %s, Maven revision %s\n' "$FAULTORA_CANDIDATE_COMMIT" "$version"
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 -B -m unittest discover -s deploy -p 'test_verify_release.py'
started="$(date +%s)"
./mvnw -B clean verify -Pstrict-qualification
python3 -B scripts/verify_qualification.py --since "$started"
./mvnw -B spotless:check
sha256sum "faultora-cli/target/faultora-$version.jar" \
  "faultora-cli/target/faultora-$version.tar.gz" "faultora-cli/target/faultora-$version.zip"
```

Keep the qualification summary before running targeted suites. Their Surefire
reports can replace reports from the strict run. Record same-commit CI and
dependency-check results, including the report date, waived findings and expiry
dates. The [dependency gate](SECURITY.md#14-supply-chain-security) blocks at
CVSS 7.0. The image publication workflow scans the OS packages of both
platform images and blocks unwaived CRITICAL findings.

Run the [offline runner image](QUALIFICATION.md#offline-runner-image-qualification)
and [extension resource](QUALIFICATION.md#extension-resource-qualification)
checks on their documented hosts. Record the host prerequisites and results.
For the two-platform image, record which native or emulated runtime executed
each architecture's checks using [runner image qualification](RUNNER_IMAGE.md#qualification).
Record the [repeated runner baseline](CAPACITY.md#repeated-runner-baseline) and
the [backup and restore rehearsal](RUNNER_SPOOL.md#back-up-and-rehearse-a-restore)
for the supported recovery path, with synthetic workload and timings.

### Upgrade and rollback

Verify the last stable release JAR and its separately downloaded `SHA256SUMS`
with the candidate verifier. The pinned comparison below is `v0.13.0`, source
commit `b90a9b35b058ff4056411e9217277c92c28a01a8`. If the comparison release
changes, record its stable tag and verified source commit.

Set `FAULTORA_BASELINE_DIR` to the absolute directory holding the two official
files. Run this in the same candidate checkout after the strict build, with
the same `version` set above.

```sh
: "${FAULTORA_BASELINE_DIR:?Set the absolute baseline asset directory}"
baseline_jar="$FAULTORA_BASELINE_DIR/faultora-0.13.0.jar"
candidate_jar="$PWD/faultora-cli/target/faultora-$version.jar"
python3 -B deploy/verify-release.py "$baseline_jar" \
  --sums "$FAULTORA_BASELINE_DIR/SHA256SUMS" --tag v0.13.0 \
  --source-digest b90a9b35b058ff4056411e9217277c92c28a01a8 --require-sums-attestation
FAULTORA_PREVIOUS_JAR="$baseline_jar" FAULTORA_NEXT_JAR="$candidate_jar" \
  FAULTORA_CHECK_MACHINE_OUTPUT_V1=true \
  ./mvnw -B verify -pl integration-tests -am \
    -Dtest=UpgradeRollbackCheck -Dsurefire.failIfNoSpecifiedTests=false
```

Record both artifact hashes, the comparison in
`integration-tests/target/upgrade-rollback/result.md`, and the runner state
checked during the [supported rollback](UPGRADE_ROLLBACK.md). This test compares
the two builds' outputs and checks journal and runner status readability. Use
the rollback guide for the operator exercise with retained runner state.

## Independent acceptance

A first-time installer uses the candidate archive and the README and
installation guide from the pinned commit in a clean environment. Provide
Java 21, the archive and its expected hash through a trusted channel, and a
synthetic service with its OpenAPI description and URL. Follow the
[independent installation check](QUALIFICATION.md#independent-installation-check)
through initialization, validation, preview, execution, readable journal and
HTML report, and the offline repeat. Record any assistance or source changes.
For a published archive, verify the release attestation before executing it.
Record an unpublished run as a candidate rehearsal and separately check the
published archive.

An independent security reviewer assesses the exact commit, deployment
profile, and intended operating conditions. Start with the
[security architecture](SECURITY.md) and the relevant deployment instructions.
Record findings against the following scope and link to reproducible evidence
from synthetic test environments.

| Boundary | Evidence to review |
|---|---|
| Runner identity and destination policy | mTLS and signed-policy checks, outbound-only runner, permitted targets, and operator-controlled deployment settings |
| Runtime, cleanup, and recovery | Deadline, cancellation, rollback, replay, and storage retention evidence for the supported runner configuration |
| Extension admission and isolation | Digest approval, failed admission behavior, Bubblewrap and cgroup limits on the claimed Linux profile, and the stock image's separate capabilities |
| Sensitive evidence and diagnostics | Export and redaction coverage, artifact access and retention, and any enabled telemetry destinations and fields |
| Supply chain and offline use | Dependency and image scans, waivers, source and artifact identity, and clean-environment transfer with egress disabled |

Record the review disposition, scope, findings with severity, owner and
resolution, and evidence of retests. An unresolved critical security or
data-integrity finding blocks the 1.0 exit gate. Record both independent
results separately from automated tests.

## Published artifact verification

After the authorized stable release and runner image are published, verify
the artifacts in a clean environment. Set `FAULTORA_RELEASE_DIR` to the
absolute directory containing the official release files and `SHA256SUMS`,
`FAULTORA_RELEASE_COMMIT` to the reviewed full source commit, and
`FAULTORA_RELEASE_VERSION` to the stable Maven revision. Use a trusted
checkout of the verifier.

```sh
set -eu
: "${FAULTORA_RELEASE_DIR:?Set the release asset directory}"
: "${FAULTORA_RELEASE_COMMIT:?Set the reviewed full release commit}"
: "${FAULTORA_RELEASE_VERSION:?Set the stable release revision}"
release_version="$FAULTORA_RELEASE_VERSION"
for suffix in .jar .zip .tar.gz -sbom.json; do
  python3 -B deploy/verify-release.py \
    "$FAULTORA_RELEASE_DIR/faultora-$release_version$suffix" \
    --sums "$FAULTORA_RELEASE_DIR/SHA256SUMS" --tag "v$release_version" \
    --source-digest "$FAULTORA_RELEASE_COMMIT" --require-sums-attestation
done
```

Take the published index digest from the authorized
[runner image workflow](RUNNER_IMAGE.md#publication) and set
`FAULTORA_RUNNER_INDEX_DIGEST` to its full `sha256:…` value. Use that immutable
reference for attestation, platform inspection and subsequent pulls.

```sh
: "${FAULTORA_RUNNER_INDEX_DIGEST:?Set the published runner index digest}"
image="ghcr.io/malatiel/faultora-runner@$FAULTORA_RUNNER_INDEX_DIGEST"
gh attestation verify "oci://$image" --repo Malatiel/Faultora \
  --signer-workflow Malatiel/Faultora/.github/workflows/runner-image.yml \
  --source-ref refs/heads/main --deny-self-hosted-runners
docker buildx imagetools inspect "$image"
```

Record the workflow run that built the image from the verified release JAR,
the `linux/amd64` and `linux/arm64` results, its digest, and its attestation.
The runner image attestation uses the publication workflow's
`refs/heads/main`. The CLI attestations use the release tag.

For the recipient drill, transfer the verified application and optional
digest-verified image using the
[air-gap procedure](../deploy/README.md#carrying-a-bundle-across-an-air-gap).
Record the transfer archive hash, the independently communicated manifest
fingerprint, the verified release `SHA256SUMS`, and the no-egress observation.
Keep the release SBOM and attestation evidence with the qualification record.
The offline transfer bundle contains its own file checksums and verifier.

## Acceptance record

| Item | Candidate identity | Status | Evidence or blocker |
|---|---|---|---|
| Commit, Maven revision, and build hashes | | Pending | |
| Fresh strict Linux reports, format, CI and security scans | | Pending | |
| Upgrade, rollback and replay | | Pending | |
| Resource isolation, supported image platforms and offline handoff | | Pending | |
| Repeated runner and restore rehearsal | | Pending | |
| Independent first-time installation | | Pending | |
| Independent sensitive-environment security review | | Pending | |
| Stable release files, SBOM, image provenance and clean installation | | Pending | |

Use sanitized evidence references in the record. Keep credentials, local
machine details, and private findings in an appropriately restricted channel.
