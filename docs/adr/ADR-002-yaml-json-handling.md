# ADR-002: YAML and JSON handling

## Status

Accepted

## Context

Faultora must parse YAML scenario documents and OpenAPI specifications, and
serialize/deserialize JSON for the canonical model, run events, and reports.
The security architecture requires safe YAML parsing that prevents code
execution through type construction.

## Decision

- Use **Jackson** (`jackson-core`, `jackson-annotations`, `jackson-databind`)
  for all JSON processing.
- Use **Jackson YAML dataformat** (`jackson-dataformat-yaml`) which wraps
  SnakeYAML for YAML parsing.
- Use **SnakeYAML 2.x** as the underlying YAML parser.
- Enforce `SafeConstructor` (or equivalent `LoaderOptions` configuration) to
  prevent arbitrary object instantiation during YAML parsing.
- Use Jackson's `@JsonTypeInfo`/`@JsonSubTypes` for sealed type
  polymorphism in the model module.
- Use nullable fields with `@JsonInclude(NON_NULL)` instead of `Optional`
  fields in serializable records (Jackson-core cannot serialize `Optional`
  without `jackson-datatype-jdk8`).

## Rejected alternatives

- **Jackson + jackson-datatype-jdk8 for Optional**: Adds an extra dependency
  to the model module. Nullable fields with `@JsonInclude(NON_NULL)` are
  simpler and more portable.
- **Gson**: Less feature-complete for sealed types and polymorphic
  serialization.
- **SnakeYAML standalone**: Jackson's YAML dataformat provides a unified API
  and integrates with the same ObjectMapper used for JSON.

## Consequences

- YAML parsing is safe by default (no arbitrary type construction).
- Model records use nullable fields instead of Optional for JSON compatibility.
- All YAML/JSON processing goes through Jackson, simplifying the dependency
  graph.
- Architecture tests must verify that no `Yaml(Constructor.class)`
  instantiation exists outside an approved location.
