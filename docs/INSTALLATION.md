# Installing the CLI

Faultora needs an existing Java 21 or newer. Maven is needed only to build from
source. The launchers use `JAVA_HOME/bin/java` when `JAVA_HOME` is set, otherwise
`java` on `PATH`. They do not download Java, change system configuration, or
add anything to `PATH`.

## Obtaining an archive

The CLI build produces `faultora-VERSION.zip` and `faultora-VERSION.tar.gz` in
`faultora-cli/target/`. Both contain the same application and both launchers.
The tar.gz preserves the executable bit for Linux; ZIP is convenient on Windows.

Published archives are downloaded from the corresponding
[GitHub release](https://github.com/Malatiel/Faultora/releases), together with
`SHA256SUMS`. Releases from 0.11.2 publish both archives; earlier releases
provide the standalone JAR only, installed as described in
[the README](../README.md#standalone-jar). To build the archives from a
checkout instead:

```sh
./mvnw package -DskipTests
```

On Windows, use `.\mvnw.cmd package -DskipTests`. The filenames use the revision
from the parent POM. The commands below use the current source version; choose
the version of your downloaded release or local build.

## Linux

For downloaded release assets, check the archive before extracting it — see
[Verifying a download](#verifying-a-download) for what each step proves.

```sh
FAULTORA_VERSION=0.11.2
grep " faultora-${FAULTORA_VERSION}.tar.gz$" SHA256SUMS \
  | sha256sum --check --strict -
gh attestation verify "faultora-${FAULTORA_VERSION}.tar.gz" --repo Malatiel/Faultora \
  --signer-workflow Malatiel/Faultora/.github/workflows/release.yml \
  --source-ref "refs/tags/v${FAULTORA_VERSION}" --deny-self-hosted-runners
tar -xzf "faultora-${FAULTORA_VERSION}.tar.gz"
cd "faultora-${FAULTORA_VERSION}"
./bin/faultora --version
./bin/faultora validate \
  --scenario examples/payment-service/scenarios/passing.yaml \
  --openapi examples/payment-service/openapi.yaml
```

For a local build, use the archive in `faultora-cli/target/` and omit the
release checksum step. Validation reads local files and does not contact the
example service. Running a scenario with `test` needs its target service and
any other dependencies described by the example.

The launcher works from another working directory too. Its JAR path is
relative to the launcher, while scenario paths remain relative to your current
directory. It preserves argument boundaries and the CLI's exit code. If you
unpack the ZIP on Linux and the extraction tool discards permissions, run
`chmod +x bin/faultora` in the extracted directory before invoking it.
The Linux launcher follows absolute and relative symbolic links, including
chains of links, using `readlink` on `PATH`. A link to `bin/faultora` can therefore
live outside the installation directory; the JAR is found beside the resolved
launcher, and input paths still use the caller's current directory.

## Windows PowerShell

This check requires exactly one matching archive entry and compares it to the
downloaded file. It works without changing PowerShell execution policy; the
launcher is a `.cmd` file.

```powershell
$version = '0.11.2'
$archive = "faultora-$version.zip"
$entries = @(Get-Content -LiteralPath SHA256SUMS |
    Where-Object { $_ -match ("^[0-9a-f]{64}  " + [regex]::Escape($archive) + '$') })
if ($entries.Count -ne 1) { throw 'Missing or duplicate archive checksum' }
$expected = $entries[0].Substring(0, 64)
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
    throw 'Archive checksum mismatch'
}
gh attestation verify $archive --repo Malatiel/Faultora `
    --signer-workflow Malatiel/Faultora/.github/workflows/release.yml `
    --source-ref "refs/tags/v$version" --deny-self-hosted-runners
if ($LASTEXITCODE -ne 0) { throw 'Archive attestation refused' }
Expand-Archive -LiteralPath $archive -DestinationPath .
Set-Location -LiteralPath "faultora-$version"
.\bin\faultora.cmd --version
.\bin\faultora.cmd validate `
    --scenario examples/payment-service/scenarios/passing.yaml `
    --openapi examples/payment-service/openapi.yaml
```

For a local build, use the ZIP in `faultora-cli\target\` and omit the release
checksum step. An invalid `JAVA_HOME` causes the launcher to fail rather than
silently select a different runtime. Unset it to select Java from `PATH`.

## Verifying a download

Two checks, and they answer different questions.

The **checksum** says the file is the one `SHA256SUMS` lists. It catches a
damaged or truncated download. It does not say who made the file: anyone able
to replace the archive can replace `SHA256SUMS` beside it.

The **attestation** says this repository's release workflow built these exact
bytes. GitHub records it when a release is published; `gh attestation verify`
looks it up by the file's digest. The flags above narrow what counts:
`--signer-workflow` accepts only `.github/workflows/release.yml`, so an
attestation from another workflow or a fork does not pass; `--source-ref`
requires the run to have been for this release's tag; and
`--deny-self-hosted-runners` requires a GitHub-hosted runner. A modified file
has a different digest and no attestation at all; a file built anywhere else
has none from this workflow and tag. Both are refused. It needs the
[GitHub CLI](https://cli.github.com/) and network access to GitHub.

The same checks apply to the JAR, the ZIP, the tar.gz and the SBOM of a
release. From a checkout, `deploy/verify-release.py` runs both for one file and
also checks that a JAR names the release version:

```sh
python3 deploy/verify-release.py faultora-VERSION.tar.gz --sums SHA256SUMS --tag vVERSION
```

Its `--checksum-only` mode skips the attestation and says so; use it only
where GitHub cannot be reached, knowing that it detects damage, not
substitution. Runner images carry an attestation of their own; see
[Runner image](RUNNER_IMAGE.md#publication).

## Layout and trust

```text
faultora-VERSION/
  bin/faultora
  bin/faultora.cmd
  lib/faultora-VERSION.jar
  LICENSE
  NOTICE
  THIRD-PARTY.txt
  README.md
  CHANGELOG.md
  deploy/README.md              runner deployment guide
  docs/
  examples/                     YAML/JSON catalogs and scenarios, CI, guides
```

The single JAR in `lib/` is the same merged executable as the standalone
release JAR. The launchers use `java -jar` and name that exact file; they do not
scan `lib/*.jar` or load adjacent plugin JARs. Built-in providers therefore keep
the merged-artifact trust check from
[ADR-004](adr/ADR-004-extension-discovery.md). This archive adds no new plugin
installation mechanism. Keep the runtime and plugin locations writable only
by the operator or deployment mechanism, as required by that ADR.

Documentation, example data and source code are not inserted into the fat JAR.
Example Java sources, compiled sample applications and source-tree build files
are not included in the CLI archive. The executable retains dependency metadata
and third-party notices. Examples that need a sample service still require it
to be built or deployed separately.

To update, extract the new version into a separate directory and point your
shell or automation at its `bin` directory. Keep results, secrets, policies and
your own scenarios outside the installation. To remove an installation, remove
its versioned directory and any `PATH` entry you added yourself.
