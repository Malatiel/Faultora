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
`SHA256SUMS`. Archive publication starts with the first release containing this
packaging. The already published v0.11.1 provides the standalone JAR instead;
its installation commands remain in [the README](../README.md#standalone-jar).
To try archives before that release, build from a checkout:

```sh
./mvnw package -DskipTests
```

On Windows, use `.\mvnw.cmd package -DskipTests`. The filenames use the revision
from the parent POM. The commands below use the current source version; choose
the version of your downloaded release or local build.

## Linux

For downloaded release assets, compare the archive against its SHA-256 entry
before extracting it. Download both files from the same release. A checksum
detects a damaged download; it does not authenticate the publisher by itself.
Public release artifacts also have GitHub build attestations, which can be
verified with `gh attestation verify ARCHIVE --repo Malatiel/Faultora`.

```sh
FAULTORA_VERSION=0.11.1
grep " faultora-${FAULTORA_VERSION}.tar.gz$" SHA256SUMS \
  | sha256sum --check --strict -
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

## Windows PowerShell

This check requires exactly one matching archive entry and compares it to the
downloaded file. It works without changing PowerShell execution policy; the
launcher is a `.cmd` file.

```powershell
$version = '0.11.1'
$archive = "faultora-$version.zip"
$entries = @(Get-Content -LiteralPath SHA256SUMS |
    Where-Object { $_ -match ("^[0-9a-f]{64}  " + [regex]::Escape($archive) + '$') })
if ($entries.Count -ne 1) { throw 'Missing or duplicate archive checksum' }
$expected = $entries[0].Substring(0, 64)
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
    throw 'Archive checksum mismatch'
}
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
  docs/
  examples/                     YAML/JSON catalogs and scenarios, example guides
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
