# The runner, as a container that dials out.
#
# Distroless: no shell, no package manager, no busybox. A runner holds key
# material and a database password's handle, and the smaller the thing around
# it the less there is to reach that material with. The cost is that nothing
# inside can be scripted — which is why the health probe is a Faultora command
# rather than a `curl` or a `test -f`.
#
# The jar is built outside and copied in. A multi-stage build that ran Maven
# here would need the network on every build, and an offline installation is
# one of the things this packaging exists for.
#
#   ./mvnw -o package -DskipTests
#   docker build --build-arg JAR=faultora-cli/target/faultora-1.0.1.jar \
#                -t faultora/runner:1.0.1 .
#
# Pin the multi-platform index, not an amd64-only child manifest. Buildx
# selects the matching Java runtime; the already-built JAR is architecture neutral.
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:6b60e44d652d66a9e2e584364c96adac4df7133674b2834b6f71741b7cdb1ff3

ARG JAR
ARG VERSION=1.0.1

LABEL org.opencontainers.image.title="Faultora runner" \
      org.opencontainers.image.description="Reliability test runner for private networks" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"

COPY ${JAR} /opt/faultora/faultora.jar

# Somewhere to write journals and the status file. Owned by the user the image
# runs as, so the container needs no write access to anything else — mount a
# volume here if the journals should outlive the container, and read the note
# in deploy/kubernetes/runner.yaml about what an emptyDir gives up.
COPY --chown=nonroot:nonroot deploy/work /var/faultora

WORKDIR /var/faultora
USER nonroot

ENTRYPOINT ["java", "-jar", "/opt/faultora/faultora.jar"]

# Nothing sensible to do by default: a runner without a dispatcher, key
# material and a policy key is not a runner, and starting one that refuses is
# less useful than saying what it needs.
CMD ["runner", "--help"]
