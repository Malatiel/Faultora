#!/usr/bin/env sh
#
# Check an offline bundle on the machine that will run it, before anything in
# it is run.
#
# Matching SHA256SUMS proves only that the files agree with the list that came
# with them: whoever could change a file could change the list, and this
# script with it. Trust has to come from outside the bundle, as the SHA256SUMS
# fingerprint offline-bundle.sh printed and the sender recorded apart from the
# medium. This script compares it first and runs nothing from the bundle unless
# it matches; the recipient checks the fingerprint and this script with the
# system's own sha256sum before running it at all (deploy/README.md).
#
# Then it refuses a file that does not match, a listed file that is missing, a
# file the list does not name, a link, and a path that leaves the bundle; given
# the release's own SHA256SUMS, checked with `gh attestation verify` before it
# crossed, a jar that is not the released one. Last, it runs the bundled CLI on
# the bundled example, which needs nothing but a local Java 21.
#
#   sh verify-offline-bundle.sh --fingerprint HEX [--release-sums SHA256SUMS]
#   sh verify-offline-bundle.sh --self-consistency-only
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

FINGERPRINT=""
RELEASE_SUMS=""
SELF_ONLY=false
while [ $# -gt 0 ]; do
    case "$1" in
        --fingerprint)
            [ $# -ge 2 ] || fail "--fingerprint needs the recorded SHA-256 of SHA256SUMS"
            FINGERPRINT="$2"
            shift 2
            ;;
        --release-sums)
            [ $# -ge 2 ] || fail "--release-sums needs a file"
            RELEASE_SUMS="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
            shift 2
            ;;
        --self-consistency-only)
            SELF_ONLY=true
            shift
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

if [ -z "${FINGERPRINT}" ] && [ "${SELF_ONLY}" = false ]; then
    fail "no --fingerprint: pass the SHA256SUMS fingerprint recorded when the bundle was made," \
         "or --self-consistency-only to check the files against their own list and run nothing"
fi
if [ -n "${FINGERPRINT}" ] && [ "${SELF_ONLY}" = true ]; then
    fail "--fingerprint and --self-consistency-only exclude each other"
fi
if [ -n "${FINGERPRINT}" ]; then
    printf '%s\n' "${FINGERPRINT}" | grep -Eqx '[0-9a-f]{64}' \
        || fail "--fingerprint is not a SHA-256: ${FINGERPRINT}"
fi

cd "$(dirname "$0")"

[ -f SHA256SUMS ] || fail "no SHA256SUMS here; this is not a complete bundle"
if [ -n "${FINGERPRINT}" ]; then
    actual="$(sha256 SHA256SUMS | cut -c1-64)"
    [ "${actual}" = "${FINGERPRINT}" ] \
        || fail "SHA256SUMS fingerprint is ${actual}, not the recorded ${FINGERPRINT}; nothing here was run"
fi

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

count="$(printf '%s\n' "${listed}" | wc -l | tr -d ' ')"
if [ "${SELF_ONLY}" = true ]; then
    echo "Bundle ${VERSION}: ${count} files match SHA256SUMS and nothing is unlisted."
    echo "NOT a trust check: nothing outside the bundle vouched for SHA256SUMS, so the CLI was not run."
    echo "SHA256SUMS fingerprint: $(sha256 SHA256SUMS | cut -c1-64)"
    exit 0
fi

command -v java >/dev/null 2>&1 || fail "no java on PATH; the bundle runs on an existing Java 21"
version="$(java -jar faultora.jar --version 2>/dev/null)" || fail "the bundled CLI did not start"
version="$(printf '%s\n' "${version}" | tr -d '\r' | sed -n '1p')"
[ "${version}" = "faultora ${VERSION}" ] \
    || fail "the bundled CLI reports '${version}', not faultora ${VERSION}"
java -jar faultora.jar validate \
    --scenario examples/payment-service/scenarios/passing.yaml \
    --openapi examples/payment-service/openapi.yaml >/dev/null 2>&1 \
    || fail "the bundled CLI could not validate the bundled example"

echo "Bundle ${VERSION} verified: SHA256SUMS is the recorded one, ${count} files match it," \
     "nothing is unlisted, and the CLI runs."
if [ -z "${RELEASE_SUMS}" ]; then
    echo "The jar was not compared with a release; --release-sums checks that it is the published one."
fi
