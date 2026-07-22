# ADR-004: Extension discovery

## Status

Accepted

## Context

Faultora needs a mechanism to discover and load extension implementations
(importers, connectors, fault providers, assertion providers, renderers) at
runtime. The mechanism must work for built-in extensions and support future
out-of-process extensions.

## Decision

- Use **Java SPI (`ServiceLoader`)** for extension discovery in M0 and M1.
- Each extension type defines a service interface in `faultora-spi`.
- Implementations register via `META-INF/services/` files.
- The engine discovers extensions at startup and validates capabilities.
- Future out-of-process extensions will use a versioned RPC contract; the SPI
  interface design must be compatible with this evolution.

## Rejected alternatives

- **Spring auto-configuration**: Requires Spring Framework dependency.
  Overkill for a CLI tool.
- **Manual registration**: Error-prone and doesn't support plugin discovery.
- **OSGi**: Overly complex for the initial use case.

## Consequences

- Built-in extensions are discovered automatically from the classpath.
- Extension isolation is limited to classloader boundaries in M0/M1.
- The SPI interface design must not expose implementation details that would
  break when extensions move to separate processes.
- Adding a new extension requires a `META-INF/services/` registration file.
