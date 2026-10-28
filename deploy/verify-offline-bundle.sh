#!/usr/bin/env sh
#
# Check an offline bundle on the machine that will run it, before anything in
# it is trusted.
#
# Matching SHA256SUMS proves the files agree with the list that came with
# them; whoever could change a file could also change the list. So this also
# refuses anything the list does not name, anything it names that is missing,
# links and paths that leave the bundle, and — given the release's own
# SHA256SUMS, checked with `gh attestation verify` before it crossed — a jar
# that is not the released one. Last, it runs the bundled CLI against the
# bundled example, which needs nothing but a local Java 21.
#
#   tar xzf faultora-VERSION-offline.tar.gz
#   sh faultora-VERSION-offline/verify-offline-bundle.sh [--release-sums SHA256SUMS]
#
set -eu

fail() {
    echo "verify-offline-bundle: $*" >&2
    exit 1
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

RELEASE_SUMS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --release-sums)
            [ $# -ge 2 ] || fail "--release-sums needs a file"
            RELEASE_SUMS="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
            shift 2
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

cd "$(dirname "$0")"

[ -f SHA256SUMS ] || fail "no SHA256SUMS here; this is not a complete bundle"
[ -f VERSION ] || fail "no VERSION here; this is not a complete bundle"
VERSION="$(cat VERSION)"
printf '%s\n' "${VERSION}" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+' \
    || fail "VERSION is not a release version: ${VERSION}"

special="$(find . ! -type f ! -type d | head -n 1)"
[ -z "${special}" ] || fail "contains a link or special file: ${special}"

if grep -Evq '^[0-9a-f]{64}  \./[^/]' SHA256SUMS; then
    fail "malformed SHA256SUMS line: $(grep -Ev '^[0-9a-f]{64}  \./[^/]' SHA256SUMS | head -n 1)"
fi
if cut -c67- SHA256SUMS | grep -Eq '(^|/)\.\.(/|$)'; then
    fail "SHA256SUMS names a path outside the bundle"
fi

listed="$(cut -c67- SHA256SUMS | sort)"
present="$(find . -type f ! -path ./SHA256SUMS | sort)"
for file in ${present}; do
    printf '%s\n' "${listed}" | grep -Fqx "${file}" || fail "not listed in SHA256SUMS: ${file}"
done
for file in ${listed}; do
    [ -f "${file}" ] || fail "listed in SHA256SUMS but missing: ${file}"
done
for file in ./faultora.jar ./LICENSE ./NOTICE ./THIRD-PARTY.txt ./VERSION ./verify-offline-bundle.sh \
        ./examples/payment-service/openapi.yaml ./examples/payment-service/scenarios/passing.yaml; do
    printf '%s\n' "${listed}" | grep -Fqx "${file}" || fail "a complete bundle has ${file}"
done

if ! report="$(sha256 -c SHA256SUMS 2>&1)"; then
    fail "files do not match SHA256SUMS:
$(printf '%s\n' "${report}" | grep -v ': OK$')"
fi

if [ -n "${RELEASE_SUMS}" ]; then
    [ -f "${RELEASE_SUMS}" ] || fail "no release checksums at ${RELEASE_SUMS}"
    released="$(grep -E "^[0-9a-f]{64}  \*?faultora-${VERSION}\.jar$" "${RELEASE_SUMS}" | cut -c1-64)"
    [ -n "${released}" ] || fail "the release checksums have no entry for faultora-${VERSION}.jar"
    bundled="$(grep -E '^[0-9a-f]{64}  \./faultora\.jar$' SHA256SUMS | cut -c1-64)"
    [ "${bundled}" = "${released}" ] || fail "faultora.jar is not the released faultora-${VERSION}.jar"
fi

command -v java >/dev/null 2>&1 || fail "no java on PATH; the bundle runs on an existing Java 21"
version="$(java -jar faultora.jar --version 2>/dev/null)" || fail "the bundled CLI did not start"
case "${version}" in
    *"faultora ${VERSION}"*) ;;
    *) fail "the bundled CLI reports '${version}', not faultora ${VERSION}" ;;
esac
java -jar faultora.jar validate \
    --scenario examples/payment-service/scenarios/passing.yaml \
    --openapi examples/payment-service/openapi.yaml >/dev/null 2>&1 \
    || fail "the bundled CLI could not validate the bundled example"

echo "Bundle ${VERSION} verified: $(printf '%s\n' "${listed}" | wc -l | tr -d ' ') files match SHA256SUMS, nothing unlisted, the CLI runs."
echo "SHA256SUMS fingerprint: $(sha256 SHA256SUMS | cut -c1-64)"
if [ -z "${RELEASE_SUMS}" ]; then
    echo "Matching checksums do not say who made the bundle. Compare the fingerprint with the one" \
         "printed when it was made, or pass --release-sums with the release's attested SHA256SUMS."
fi
