# ADR-004: Extension discovery

## Status

Accepted; amended in 0.4.0 to separate discovered extensions from
security-scoped ones.
Amended during M6 hardening for pre-instantiation validation and built-in
class identity and artifact origin.

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
- Inspect classpath service descriptors through `ServiceLoader.Provider`
  before calling `get()`. Check artifact identity, manifest, API compatibility,
  and requested capabilities before provider initialization or construction.
- Hash external artifacts incrementally with a 128 MiB byte ceiling. Read at
  most 64 KiB of uncompressed manifest data, including for directory-loaded
  providers. Directory manifests belong to the provider's code source, rather
  than whichever parent classloader first supplies a resource of that name.
- Require a nonblank manifest ID and version and a `requiresApi` range with at
  least one bound. Version bounds use nonnegative integer components separated
  by dots, optionally followed by a `-` or `+` suffix, with a maximum of 128
  characters per bound; reject overflow and reversed ranges. Reject malformed
  YAML, duplicate fields, trailing documents, unknown fields, and nesting deeper
  than 16 levels. JAR and manifest inputs must be regular files. Inspection
  failures refuse the provider with a fixed diagnostic that does not echo
  manifest contents.
- Built-in status comes from the explicit inventory of shipped implementation
  classes in the runtime's defining classloader, not a package prefix. In a
  merged release, a built-in must also originate from the runtime artifact.
  Detect that layout by inspecting the runtime's own contents, not by trusting
  where another class says it came from.
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

### In-process artifact trust boundary

Operators must restrict write access to the runtime, bootstrap classpath, and
plugin directories to the operator or deployment mechanism. A concurrent local
writer with access to those locations can replace the runtime itself. Such a
writer is outside the in-process discovery threat model: filesystem write
access is a requirement on the deployment environment, not a security boundary
enforced by Faultora.

Under that requirement, a grant for an expected artifact digest detects an
artifact replaced before startup or a different artifact supplied as the
permitted plugin. A class-name grant alone does not pin artifact bytes. These
checks do not protect against concurrent modification of the classpath or
plugin directories.

The provider class is loaded, without initialization, before its artifact
digest is calculated. The digest identifies the file read on disk, not the
class bytes already read by the classloader. Manifest inspection and class
loading are separate reads; the current in-process path does not bind them to
one immutable artifact snapshot.

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
- `ExtensionPolicy.allowedExtensions` **is consulted before instantiation**.
  An implementation outside the trusted built-in inventory joins a run only
  with an operator grant by class name or calculated artifact digest, plus a
  compatible manifest without unenforceable capabilities. Refusal is named on
  stderr without executing the provider's initializer or constructor.
- Adding a shipped built-in requires updating the trusted inventory as well as
  its service registration. Third-party service discovery remains dynamic.
- The runtime and core modules in a modular/development bootstrap classpath
  are trusted deployment inputs. This check does not authenticate replaced core
  dependencies, sandbox arbitrary classloaders, or confine an explicitly allowed
  plugin. Process isolation and resource controls remain unfinished, and a
  policy requiring them is refused (ADR-023); SEC-08 remains partially met.
- The SPI interface design must not expose implementation details that would
  break when extensions move to separate processes.

## References

- [Java 21 ServiceLoader provider inspection](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html)
