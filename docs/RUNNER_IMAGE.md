# Runner images

The runner image supports `linux/amd64` and `linux/arm64`. Each variant uses the
same packaged JAR and the corresponding Java 21 runtime from a pinned Distroless
index. It runs as `nonroot` (uid 65532), has no shell or package manager, exposes
no port, and uses the existing outbound mTLS and signed-policy runner protocol.
Deployment permissions and health probes are described in the
[runner guide](../deploy/README.md).

## Local builds

Build from the repository root with Docker and Buildx. Maven produces the JAR
outside the image; the Dockerfile runs no Maven commands and has no `RUN` step.
Creating the two architecture variants does not need QEMU. Executing one does
need a native runtime or working emulation for that architecture.

A `docker-container` builder supports OCI export across Docker image stores;
Docker with a containerd image store can also export with its existing builder.
The following creates a named builder without changing your selected default:

```sh
docker buildx create --name faultora-local --driver docker-container
./mvnw -o package -DskipTests
export VERSION="$(./mvnw -o help:evaluate -Dexpression=project.version -q -DforceStdout)"
export JAR="faultora-cli/target/faultora-${VERSION}.jar"
docker buildx bake --builder faultora-local -f deploy/runner-image.hcl
```

In PowerShell, set the two variables as follows after building the JAR:

```powershell
.\mvnw.cmd -o package -DskipTests
$env:VERSION = (.\mvnw.cmd -o help:evaluate '-Dexpression=project.version' -q -DforceStdout).Trim()
$env:JAR = "faultora-cli/target/faultora-$env:VERSION.jar"
docker buildx create --name faultora-local --driver docker-container
docker buildx bake --builder faultora-local -f deploy/runner-image.hcl
```

Reuse an existing suitable builder instead of creating it again. The default
target, `local`, writes `dist/faultora-runner-VERSION.oci.tar`: an OCI image
layout with both architecture manifests, not a publication to a registry.
`IMAGE` controls its image reference (default `faultora/runner`), and `OUTPUT`
can select a different archive filename. Buildx may require an explicit
`--allow=fs.write=/chosen/output/parent` when that output is outside the build
context. Grant only the intended output directory.

An OCI layout is distinct from the single-platform Docker save format. Image
stores differ in their ability to import multi-platform layouts. For a runtime
check, load just the intended platform with the `load` target:

```sh
docker buildx bake --builder faultora-local -f deploy/runner-image.hcl load \
  --set load.platform=linux/amd64 --set load.tags=faultora/runner:local-amd64
docker run --rm --platform linux/amd64 --network none \
  faultora/runner:local-amd64 --version
```

Use `linux/arm64` and a separate `local-arm64` tag for Arm. `load` defaults to
amd64; it does not detect or install an emulator. Docker Desktop commonly
provides emulation, but a successful multi-platform build alone proves no
runtime execution. Check the actual command result. `docker buildx inspect`
lists the builder's advertised platforms.

The existing offline-bundle script saves the image currently loaded under its
selected tag. That does not imply it contains both platforms; keep the OCI
archive when you need the complete index. You can remove the named builder
with `docker buildx rm faultora-local` when no longer needed.

## Qualification

The existing `RunnerImageE2ETest` defaults to the Docker daemon's native
architecture. Set `-Dfaultora.runner.platform=linux/arm64` (or `linux/amd64`) to
qualify an explicitly available runtime. An explicitly requested platform must
run successfully; the test does not turn an execution failure into a skip.
The runtime suite serves a real mTLS dispatch, reads fresh/stale health files,
checks refusal without a policy key, and verifies nonroot, image architecture,
no exposed port and absent shell executables.

`RunnerMultiArchBuildTest` exports a real OCI archive, checks both runtime
manifests/configs and their SHA-256 descriptors, and confirms the default target
is a local file. It uses the selected builder when it supports that export;
on the classic store it creates and removes an isolated temporary builder.
It does not publish an image.
For example, from a checkout:

```sh
./mvnw -o package -pl integration-tests -am \
  -Dtest=RunnerImageE2ETest,RunnerImageBuildArgumentTest,RunnerMultiArchBuildTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Run the runtime suite for Arm only where it can actually execute. A native
amd64 pass and an emulated arm64 pass should be reported as such. These targeted
checks do not replace final qualification of the merged release candidate.

## Publication

The separate `.github/workflows/runner-image.yml` workflow is manually dispatched
with an already published stable tag, such as `vMAJOR.MINOR.PATCH`. It is not
triggered by a branch push or an ordinary local build. Publication starts with
the first release containing this packaging; the earlier CLI releases do not
imply a published multi-platform runner image.

The workflow refuses to run unless dispatched from `main`. It checks out the
exact tag, requires a published release and downloads its existing JAR and
`SHA256SUMS`. Before any code from the tag runs, the workflow's own
`gh attestation verify` requires the JAR to carry `release.yml`'s attestation
for that tag and its commit; only then do the tag's own checks run —
`deploy/verify-release.py`, the same check a user runs on a download, for the
listed checksum and the manifest version. It does not fall back to rebuilding
the application or skip the release's security gate. It then checks both
platforms with QEMU available, logs in to GHCR using the job's package token,
and publishes one index as `ghcr.io/OWNER/REPOSITORY-runner:VERSION`. No `latest`
tag is moved. The digest it attests is the one Buildx reports for the index it
pushed, never what the tag points to afterwards, and the provenance is pushed
to the registry beside the image.

To check a pulled image was published by this workflow from `main`:

```sh
gh attestation verify oci://ghcr.io/malatiel/faultora-runner:VERSION \
  --repo Malatiel/Faultora \
  --signer-workflow Malatiel/Faultora/.github/workflows/runner-image.yml \
  --source-ref refs/heads/main \
  --deny-self-hosted-runners
```

`--source-ref` is the workflow's ref, `main`, not the release tag the workflow
checks out: without it, the same workflow run from any other branch would also
pass. Pull by the digest the verification reports, not by the tag, so what runs
is what was checked. The JAR inside was verified against the release's own
attestation before the image was built, as described above.

The image workflow's CLI/health smoke checks are smaller than the mTLS runtime
suite and the release qualification that produced the JAR. Registry publication
and its permissions must be exercised by an authorized workflow run; a local
build or workflow inspection cannot establish that remote result.

The base-image index is pinned in `Dockerfile`. Updating it requires checking
that it still contains both platforms and rerunning the applicable image
qualification. Docker verifies the OCI content digests while pulling it.

See [Docker's Bake reference](https://docs.docker.com/build/bake/reference/) and
[OCI exporter documentation](https://docs.docker.com/build/exporters/oci-docker/)
for builder/exporter requirements, and
[GitHub CLI attestation verification](https://cli.github.com/manual/gh_attestation_verify)
for the release identity checks.
