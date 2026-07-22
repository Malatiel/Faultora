# Faultora

**Break it here. Trust it everywhere.**

Faultora is a planned self-hosted reliability testing platform for APIs and
distributed systems. It will import machine-readable API descriptions, execute
repeatable failure scenarios, verify technical and business invariants, and
produce CI-friendly evidence. The platform is designed to run entirely inside
restricted customer infrastructure, including disconnected and zero-egress
environments.

The project is currently in the architecture and repository-foundation stage.
No runnable release exists yet.

## Product direction

Faultora is intended to let a team:

1. import an OpenAPI description;
2. bind operations to a test environment;
3. describe credentials through external secret references;
4. compose scenarios from requests, concurrency, faults, observations, and
   assertions;
5. run the same scenario locally, in CI, or on distributed workers;
6. receive HTML, JSON, and JUnit reports with reproducible evidence.

The initial release focuses on local and CI execution against HTTP APIs. Kafka,
distributed agents, Kubernetes orchestration, and a web interface are later
milestones.

Security is a release invariant rather than a final hardening phase. Faultora
will not require a hosted control plane, mandatory telemetry, runtime downloads,
or transmission of target traffic and reports outside the customer's chosen
storage boundary.

## Architecture at a glance

```text
OpenAPI / AsyncAPI / Protobuf
              |
              v
        Import adapters
              |
              v
     Canonical API catalog
              |
              v
        Scenario compiler
              |
              v
        Execution engine
       /       |        \
 connectors  faults   assertions
              |
              v
           reports
```

The execution engine depends only on Faultora's canonical model and extension
contracts. Protocol support and infrastructure integrations live behind
plugins, allowing the local runner and future distributed platform to execute
the same scenario format.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security architecture](docs/SECURITY.md)
- [Delivery roadmap](docs/ROADMAP.md)

## Planned first release

The first usable vertical slice will provide:

- a Java 21 command-line runner;
- a versioned YAML scenario format;
- OpenAPI import;
- an HTTP connector;
- schema, status, header, and JSONPath assertions;
- sequential, parallel, repeat, and eventual execution blocks;
- environment-based secret resolution;
- zero-egress execution with explicit target allowlists;
- metadata-only evidence capture by default;
- JSON, HTML, and JUnit reports;
- a deterministic example service and end-to-end test suite;
- containerized execution suitable for CI.

See the roadmap for the release gates and implementation work packages.
