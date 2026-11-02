---
name: Bug report
about: Report a reproducible defect with synthetic, reviewed evidence
title: ''
labels: ''
assignees: ''
---

<!-- For vulnerabilities use Security -> Report a vulnerability; see SECURITY.md.
Do not post secrets, customer data, internal URLs or whole run directories.
See SUPPORT.md for evidence and version policy. -->

## What happened

Describe the expected behavior and actual result, including the error code or
exit code if available.

## Version and environment

- Faultora version (`faultora --version` or `java -jar <jar> --version`):
- Java version, operating system and architecture:
- Execution mode (local CLI, runner or MCP):
- Docker version/availability, if relevant:

## Minimal reproduction

List the commands and a small synthetic scenario/catalog or public fixture.
Include the seed and relevant policy options if needed to reproduce. Replace
private hostnames, identifiers and values before posting; do not paste
credentials, environment dumps or raw request/response bodies.

## Diagnostic bundle (optional)

If a run was created and your CLI supports it, run:

```sh
faultora diagnose --run <run-directory> --output faultora-diagnostics.zip
```

Inspect the ZIP before attaching it: identifiers and digests are retained.
Do not attach the run directory, raw journal or resolved configuration.
If no run exists, the command is unavailable or it refuses the input, describe
that here and provide sanitized reproduction details instead.

## Reproduction frequency and workaround

State whether it happens consistently and whether a workaround exists.
