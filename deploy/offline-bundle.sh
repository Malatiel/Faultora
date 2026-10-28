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
#   deploy/offline-bundle.sh 0.11.3
#
set -eu

VERSION="${1:-0.11.3}"
IMAGE="${IMAGE:-faultora/runner:${VERSION}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${ROOT}/faultora-cli/target/faultora-${VERSION}.jar"
OUT="${OUT:-${ROOT}/dist}"
STAGE="${OUT}/faultora-${VERSION}-offline"

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

if [ ! -f "${JAR}" ]; then
    echo "No jar at ${JAR}. Run: ./mvnw -o package -DskipTests" >&2
    exit 1
fi

rm -rf "${STAGE}"
mkdir -p "${STAGE}/deploy" "${STAGE}/examples/payment-service/scenarios"

cp "${JAR}" "${STAGE}/faultora.jar"
cp -R "${ROOT}/deploy/docker-compose.yml" "${ROOT}/deploy/kubernetes" "${STAGE}/deploy/"
cp "${ROOT}/Dockerfile" "${ROOT}/LICENSE" "${ROOT}/NOTICE" "${ROOT}/THIRD-PARTY.txt" \
   "${ROOT}/deploy/README.md" "${ROOT}/deploy/verify-offline-bundle.sh" "${STAGE}/"
# One scenario and its catalog, so the recipient sees the CLI work before it
# is pointed at anything real.
cp "${ROOT}/examples/payment-service/openapi.yaml" "${STAGE}/examples/payment-service/"
cp "${ROOT}/examples/payment-service/scenarios/passing.yaml" \
   "${STAGE}/examples/payment-service/scenarios/"
printf '%s\n' "${VERSION}" > "${STAGE}/VERSION"

# The image, if one has been built here. A bundle without it still installs —
# `java -jar faultora.jar runner` is the same program — so a missing image is
# a smaller bundle rather than a failed build.
if command -v docker >/dev/null 2>&1 && docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    docker save "${IMAGE}" -o "${STAGE}/faultora-runner-${VERSION}.image.tar"
else
    echo "No image ${IMAGE} here; bundling the jar alone." >&2
fi

# Checksums over everything, computed inside the staging directory so the paths
# in the file are the paths the recipient will have, in a stable order.
( cd "${STAGE}" && find . -type f ! -name SHA256SUMS | LC_ALL=C sort \
    | while IFS= read -r file; do sha256 "${file}"; done > SHA256SUMS )
FINGERPRINT="$(cd "${STAGE}" && sha256 SHA256SUMS | cut -c1-64)"

( cd "${OUT}" && tar czf "faultora-${VERSION}-offline.tar.gz" \
    "faultora-${VERSION}-offline" )
rm -rf "${STAGE}"

echo "Wrote ${OUT}/faultora-${VERSION}-offline.tar.gz"
echo "SHA256SUMS fingerprint: ${FINGERPRINT}"
echo "Record the fingerprint apart from the bundle; on arrival run:"
echo "  tar xzf faultora-${VERSION}-offline.tar.gz && sh faultora-${VERSION}-offline/verify-offline-bundle.sh"
