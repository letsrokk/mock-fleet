#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

mkdir -p "${TEMP_DIR}/bin"
cat > "${TEMP_DIR}/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${CURL_ARGS_FILE}"
cat >/dev/null
EOF
chmod +x "${TEMP_DIR}/bin/curl"

assert_contains() {
    local expected="$1"
    local actual="$2"
    local description="$3"

    if [[ "${actual}" != *"${expected}"* ]]; then
        printf 'FAIL: %s\nMissing: %s\nActual:\n%s\n' "${description}" "${expected}" "${actual}" >&2
        exit 1
    fi
}

run_script() {
    local script="$1"
    shift
    local capture="${TEMP_DIR}/curl-args"

    CURL_ARGS_FILE="${capture}" PATH="${TEMP_DIR}/bin:${PATH}" \
        bash "${REPO_ROOT}/bin/local/${script}" "$@" >/dev/null
    cat "${capture}"
}

run_script_with_endpoint_env() {
    local script="$1"
    local capture="${TEMP_DIR}/curl-args"

    CURL_ARGS_FILE="${capture}" PATH="${TEMP_DIR}/bin:${PATH}" \
        WIREMOCK_HOST=env.localhost WIREMOCK_SCHEME=http \
        bash "${REPO_ROOT}/bin/local/${script}" >/dev/null
    cat "${capture}"
}

create_args=$(run_script wiremock-create-mapping.sh)
assert_contains 'https://mock-fleet.minikube.localhost/wiremock/__admin/mappings' "${create_args}" 'Create mapping uses the path-routed HTTPS endpoint'
assert_contains 'Host: mock-fleet.minikube.localhost' "${create_args}" 'Create mapping sends the fleet host header'

fetch_args=$(run_script wiremock-fetch-mappings.sh)
assert_contains 'https://mock-fleet.minikube.localhost/wiremock/__admin/mappings' "${fetch_args}" 'Fetch mappings uses the path-routed HTTPS endpoint'
assert_contains 'Host: mock-fleet.minikube.localhost' "${fetch_args}" 'Fetch mappings sends the fleet host header'

persist_args=$(run_script wiremock-persist-mapping.sh)
assert_contains 'https://mock-fleet.minikube.localhost/wiremock/__admin/mappings/save' "${persist_args}" 'Persist mappings uses the path-routed HTTPS endpoint'
assert_contains 'Host: mock-fleet.minikube.localhost' "${persist_args}" 'Persist mappings sends the fleet host header'

purge_args=$(run_script wiremock-purge-mappings.sh)
assert_contains 'https://mock-fleet.minikube.localhost/wiremock/__admin/mappings' "${purge_args}" 'Purge mappings uses the path-routed HTTPS endpoint'
assert_contains 'Host: mock-fleet.minikube.localhost' "${purge_args}" 'Purge mappings sends the fleet host header'

override_args=$(run_script wiremock-fetch-mappings.sh --host custom.localhost --scheme http)
assert_contains 'http://custom.localhost/wiremock/__admin/mappings' "${override_args}" 'Existing host and scheme overrides remain supported'
assert_contains 'Host: custom.localhost' "${override_args}" 'Host override controls the request header'

env_override_args=$(run_script_with_endpoint_env wiremock-fetch-mappings.sh)
assert_contains 'http://env.localhost/wiremock/__admin/mappings' "${env_override_args}" 'Existing host and scheme environment overrides remain supported'
assert_contains 'Host: env.localhost' "${env_override_args}" 'Host environment override controls the request header'

echo 'Local WireMock helper tests passed.'
