# ADR-001: Maven module layout and dependency enforcement

## Status

Accepted

## Context

Faultora needs a multi-module Maven build that enforces dependency direction
between core domain types, extension contracts, and implementation modules.
The architecture defines strict rules: model must not depend on SPI or engine;
SPI must not depend on engine or implementations; engine depends only on
interfaces.

## Decision

- Use Java 21 Maven multi-module build with a parent POM.
- Group modules by concern: `faultora-model`, `faultora-spi`, `faultora-spec`,
  `faultora-schema`, `faultora-engine`, protocol importers, connectors, fault
  providers, assertion providers, reporting, CLI, testkit, examples, and
  integration tests.
- A capability used by more than one consumer gets its own module rather than
  a home inside one of them: `faultora-schema` generates and validates values
  against catalog schemas for the engine today and for response-schema
  assertions next, and depends only on `faultora-model`.
- Enforce dependency direction with `maven-enforcer-plugin` and
  `bannedDependencies` rules.
- Centralize dependency and plugin versions in the parent POM's
  `dependencyManagement` and `pluginManagement`.
- Use Maven Wrapper (`mvnw`) for reproducible builds.

## Rejected alternatives

- **Gradle**: Maven is more widely used in enterprise Java and has better
  standardization for multi-module builds. Gradle's flexibility adds complexity
  without proportional benefit for this project size.
- **Single module**: Violates the architecture's dependency direction rules.
  Separate modules make the dependency graph explicit and enforceable.

## Consequences

- Each module has a clear responsibility and explicit dependencies.
- Build is reproducible from a fresh checkout with `./mvnw verify`.
- Adding new modules requires updating the parent POM and enforcer rules.
- Dependency versions are managed centrally, preventing version drift.
