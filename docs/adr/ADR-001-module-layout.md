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

## Amendment (0.7.0)

The rule that a capability used by more than one consumer gets its own module
was applied three times when events arrived, each time because a second
consumer appeared rather than because a module was wanted:

- **`faultora-net`** holds the destination policy. A bootstrap server is as
  reachable a destination as a base URL, and a second copy of the private-range
  rule would be a second thing to keep correct.
- **`faultora-import-common`** holds document reading and reference resolution.
  OpenAPI and AsyncAPI are different vocabularies over the same substrate.
- **`faultora-connector-kafka`** and **`faultora-import-asyncapi`** are the
  event protocol itself, kept out of the CLI's other paths: a run whose catalog
  has no Kafka target never constructs a broker client.

Two capabilities were placed in `faultora-spi` rather than in modules of their
own, because they are part of what an extension author implements against
rather than something implemented beside them: applying an evidence policy to
captured content, and the protocol-neutral shape of an observed message.

## Consequences

- Each module has a clear responsibility and explicit dependencies.
- Build is reproducible from a fresh checkout with `./mvnw verify`.
- Adding new modules requires updating the parent POM and enforcer rules.
- Dependency versions are managed centrally, preventing version drift.
