#!/usr/bin/env sh
#
# Everything needed to install a runner on a machine that cannot reach a
# network, in one file with checksums.
#
# The image is saved rather than pulled, because a Dockerfile is not an offline
# artifact: building one fetches a base image. The checksums are not decoration
# — this is the thing that crosses an air gap, usually on removable media, and
# the person who carries it has no other way to tell it arrived intact.
#
#   ./mvnw -o package -DskipTests
#   deploy/offline-bundle.sh 0.10.0
#
set -eu

VERSION="${1:-0.10.0}"
IMAGE="${IMAGE:-faultora/runner:${VERSION}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${ROOT}/faultora-cli/target/faultora-${VERSION}.jar"
OUT="${ROOT}/dist"
STAGE="${OUT}/faultora-${VERSION}-offline"

if [ ! -f "${JAR}" ]; then
    echo "No jar at ${JAR}. Run: ./mvnw -o package -DskipTests" >&2
    exit 1
fi

rm -rf "${STAGE}"
mkdir -p "${STAGE}/deploy"

cp "${JAR}" "${STAGE}/faultora.jar"
cp -R "${ROOT}/deploy/docker-compose.yml" "${ROOT}/deploy/kubernetes" "${STAGE}/deploy/"
cp "${ROOT}/Dockerfile" "${ROOT}/LICENSE" "${ROOT}/NOTICE" "${ROOT}/THIRD-PARTY.txt" \
   "${ROOT}/deploy/README.md" "${STAGE}/"

# The image, if one has been built here. A bundle without it still installs —
# `java -jar faultora.jar runner` is the same program — so a missing image is
# a smaller bundle rather than a failed build.
if docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    docker save "${IMAGE}" -o "${STAGE}/faultora-runner-${VERSION}.image.tar"
else
    echo "No image ${IMAGE} here; bundling the jar alone." >&2
fi

# Checksums over everything, computed inside the staging directory so the paths
# in the file are the paths the recipient will have.
( cd "${STAGE}" && find . -type f ! -name SHA256SUMS -exec shasum -a 256 {} + \
    > SHA256SUMS )

( cd "${OUT}" && tar czf "faultora-${VERSION}-offline.tar.gz" \
    "faultora-${VERSION}-offline" )
rm -rf "${STAGE}"

echo "Wrote ${OUT}/faultora-${VERSION}-offline.tar.gz"
echo "Verify on arrival with:  tar xzf ... && cd faultora-${VERSION}-offline && shasum -a 256 -c SHA256SUMS"
