#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
FIXTURE_ROOT=$(mktemp -d)

cleanup() {
    rm -rf "${FIXTURE_ROOT}"
}

fail() {
    echo "$1" >&2
    exit 1
}

assert_contains() {
    local value="$1"
    local expected="$2"
    [[ "${value}" == *"${expected}"* ]] || fail "Expected output to contain: ${expected}"
}

assert_not_contains() {
    local value="$1"
    local unexpected="$2"
    [[ "${value}" != *"${unexpected}"* ]] || fail "Expected output not to contain: ${unexpected}"
}

write_executable() {
    local path="$1"
    shift
    printf '%s\n' "$@" >"${path}"
    chmod +x "${path}"
}

trap cleanup EXIT

make_output=$(make --no-print-directory -n -C "${REPO_ROOT}" local-deploy)
assert_not_contains "${make_output}" "--rebuild"

for rebuild in dash api proxy mcp all; do
    make_output=$(make --no-print-directory -n -C "${REPO_ROOT}" local-deploy REBUILD="${rebuild}")
    assert_contains "${make_output}" "--rebuild '${rebuild}'"
done

if make --no-print-directory -n -C "${REPO_ROOT}" local-deploy REBUILD=invalid >"${FIXTURE_ROOT}/make-invalid.out" 2>&1; then
    fail "Expected Makefile to reject an invalid REBUILD value"
fi
assert_contains "$(<"${FIXTURE_ROOT}/make-invalid.out")" "REBUILD must be false, dash, api, proxy, mcp, or all"

FAKE_REPO="${FIXTURE_ROOT}/repo"
FAKE_BIN="${FIXTURE_ROOT}/bin"
CALLS_FILE="${FIXTURE_ROOT}/calls"
mkdir -p \
    "${FAKE_REPO}/bin/local" \
    "${FAKE_REPO}/deploy/helm/mock-fleet" \
    "${FAKE_REPO}/fleet-api" \
    "${FAKE_REPO}/fleet-proxy" \
    "${FAKE_REPO}/fleet-mcp" \
    "${FAKE_REPO}/fleet-dash" \
    "${FAKE_BIN}"
cp "${REPO_ROOT}/bin/local/deploy.sh" "${FAKE_REPO}/bin/local/deploy.sh"
touch "${FAKE_REPO}/deploy/helm/mock-fleet/values.yaml"
touch "${FAKE_REPO}/deploy/helm/mock-fleet/values.minikube.yaml"

write_executable "${FAKE_BIN}/minikube" \
    '#!/usr/bin/env bash' \
    'if [[ "$1" == "status" ]]; then printf "Running\n"; fi'
write_executable "${FAKE_BIN}/helm" \
    '#!/usr/bin/env bash' \
    'exit 0'
write_executable "${FAKE_BIN}/git" \
    '#!/usr/bin/env bash' \
    'if [[ "$1" == "diff" && -n "${GIT_CHANGED_FILES:-}" ]]; then printf "%s\n" "${GIT_CHANGED_FILES}"; fi' \
    'exit 0'
write_executable "${FAKE_BIN}/docker" \
    '#!/usr/bin/env bash' \
    'printf "docker %s\n" "$*" >>"${CALLS_FILE}"'
write_executable "${FAKE_BIN}/kubectl" \
    '#!/usr/bin/env bash' \
    'if [[ "$1" == "create" ]]; then printf "apiVersion: v1\n"; exit 0; fi' \
    'if [[ "$1" == "get" ]]; then' \
    '  for argument in "$@"; do' \
    '    if [[ "${argument}" == *"app.kubernetes.io/component="* ]]; then' \
    '      printf "mock-fleet-%s" "${argument##*=}"' \
    '      exit 0' \
    '    fi' \
    '  done' \
    'fi' \
    'exit 0'
write_executable "${FAKE_REPO}/fleet-api/mvnw" \
    '#!/usr/bin/env bash' \
    'printf "api-mvnw %s\n" "$*" >>"${CALLS_FILE}"'
write_executable "${FAKE_REPO}/fleet-proxy/mvnw" \
    '#!/usr/bin/env bash' \
    'printf "proxy-mvnw %s\n" "$*" >>"${CALLS_FILE}"'

run_deploy() {
    local rebuild="$1"
    local changed_files="${2:-}"
    : >"${CALLS_FILE}"
    PATH="${FAKE_BIN}:${PATH}" CALLS_FILE="${CALLS_FILE}" GIT_CHANGED_FILES="${changed_files}" \
        "${FAKE_REPO}/bin/local/deploy.sh" --rebuild "${rebuild}"
}

output=$(run_deploy false)
assert_contains "${output}" "Modules selected for image rebuild: none."
[[ ! -s "${CALLS_FILE}" ]] || fail "Expected the default deployment to skip image builds in a clean existing release"

output=$(run_deploy mcp)
assert_contains "${output}" "Modules selected for image rebuild: mcp."
assert_contains "$(<"${CALLS_FILE}")" "api-mvnw -f ${FAKE_REPO}/fleet-mcp/pom.xml"
assert_not_contains "$(<"${CALLS_FILE}")" "proxy-mvnw"
assert_not_contains "$(<"${CALLS_FILE}")" "docker build"

output=$(run_deploy mcp 'fleet-api/pom.xml')
assert_contains "${output}" "Modules selected for image rebuild: api, mcp."
calls=$(<"${CALLS_FILE}")
assert_contains "${calls}" "api-mvnw clean package -DskipTests"
assert_contains "${calls}" "api-mvnw -f ${FAKE_REPO}/fleet-mcp/pom.xml"

output=$(run_deploy mcp 'fleet-mcp/pom.xml')
assert_contains "${output}" "Modules selected for image rebuild: mcp."

output=$(run_deploy api)
assert_contains "${output}" "Modules selected for image rebuild: api."
assert_contains "$(<"${CALLS_FILE}")" "api-mvnw clean package -DskipTests"
assert_not_contains "$(<"${CALLS_FILE}")" "fleet-mcp/pom.xml"

output=$(run_deploy proxy)
assert_contains "${output}" "Modules selected for image rebuild: proxy."
assert_contains "$(<"${CALLS_FILE}")" "proxy-mvnw clean package -DskipTests"

output=$(run_deploy dash)
assert_contains "${output}" "Modules selected for image rebuild: dash."
assert_contains "$(<"${CALLS_FILE}")" "docker build -t ghcr.io/letsrokk/mock-fleet/dash:latest ${FAKE_REPO}/fleet-dash"

output=$(run_deploy all)
assert_contains "${output}" "Modules selected for image rebuild: proxy, api, mcp, dash."
calls=$(<"${CALLS_FILE}")
assert_contains "${calls}" "proxy-mvnw clean package -DskipTests"
assert_contains "${calls}" "api-mvnw clean package -DskipTests"
assert_contains "${calls}" "api-mvnw -f ${FAKE_REPO}/fleet-mcp/pom.xml"
assert_contains "${calls}" "docker build -t ghcr.io/letsrokk/mock-fleet/dash:latest ${FAKE_REPO}/fleet-dash"

if PATH="${FAKE_BIN}:${PATH}" CALLS_FILE="${CALLS_FILE}" \
    "${FAKE_REPO}/bin/local/deploy.sh" --rebuild invalid >"${FIXTURE_ROOT}/deploy-invalid.out" 2>&1; then
    fail "Expected deploy.sh to reject an invalid rebuild target"
fi
assert_contains "$(<"${FIXTURE_ROOT}/deploy-invalid.out")" "Invalid rebuild target: invalid. Expected false, dash, api, proxy, mcp, or all."

echo "Local deploy rebuild selection tests passed"
