# ADR-004: Extension discovery

## Status

Accepted; amended in 0.4.0 to separate discovered extensions from
security-scoped ones.

## Context

Faultora needs a mechanism to discover and load extension implementations
(importers, connectors, fault providers, assertion providers, renderers) at
runtime. The mechanism must work for built-in extensions and support future
out-of-process extensions.

Not every extension is equal from a safety point of view. An assertion
provider reads evidence that has already been captured. A connector decides
which hosts a run may reach, and a fault provider decides what may be broken
and where. Discovering the second kind from the classpath would mean that
adding a jar can widen what a run is allowed to touch — the opposite of the
project's "no implicit egress" and "safe defaults" principles.

## Decision

- Use **Java SPI (`ServiceLoader`)** for extension discovery.
- Each extension type defines a service interface in `faultora-spi`, and
  implementations register via `META-INF/services/` files.
- **Discovered from the classpath:** source importers, assertion providers,
  and report renderers. The CLI resolves them by their own metadata — an
  importer by source family, an assertion provider by `type()`, a renderer by
  `format()` — so `--format` and `assertionType` accept whatever is installed.
- **Constructed explicitly by the composition root:** connectors and fault
  providers. Both take operator-supplied configuration that bounds the blast
  radius of a run — the destination policy behind `--allow-private`, and the
  Toxiproxy admin endpoint behind `--toxiproxy-url`, which also decides which
  fault types the execution policy allows. A scenario or a jar on the
  classpath must not be able to supply either.
- Future out-of-process extensions will use a versioned RPC contract; the SPI
  interface design must be compatible with this evolution.

## Rejected alternatives

- **Spring auto-configuration**: Requires Spring Framework dependency.
  Overkill for a CLI tool.
- **Manual registration for everything**: what 0.1.0–0.3.1 actually shipped —
  the service files existed but nothing loaded them, so every new assertion
  type or report format required editing the composition root. Error-prone,
  and it made the SPI a compile-time interface rather than an extension point.
- **Discovering connectors and fault providers too**: uniform, but it would
  let classpath contents decide what a run may reach or break, and neither
  contract can be constructed without run-scoped security configuration.
- **OSGi**: Overly complex for the initial use case.

## Consequences

- Adding an assertion type, report format, or importer is a matter of putting
  a module on the classpath with a `META-INF/services/` entry; the CLI needs
  no change. The shaded release JAR merges those files, so discovery works
  identically from the packaged artifact.
- Adding a connector or fault provider still requires a deliberate change to
  the composition root — accepted, because that is where the policy that
  bounds it is assembled.
- `ExtensionPolicy.allowedExtensions` **is consulted at discovery**. An
  implementation outside the project's own package joins a run only when the
  operator names it with `--allow-extension`; otherwise it is refused and
  named on stderr. Identity is checked by class name, which is what a
  classpath can offer: verifying a digest, and isolating an extension from the
  run, arrive with the out-of-process plugin protocol (M6-02), and SEC-08 is
  satisfied only in part until then.
- Extension isolation remains limited to classloader boundaries until the
  out-of-process plugin protocol (M6-02).
- The SPI interface design must not expose implementation details that would
  break when extensions move to separate processes.
