#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

TEST_REPO="${TEMP_DIR}/repo"
STUB_BIN="${TEMP_DIR}/bin"
COMMAND_LOG="${TEMP_DIR}/commands"

mkdir -p \
    "${TEST_REPO}/bin/local" \
    "${TEST_REPO}/deploy/helm/mock-fleet" \
    "${TEST_REPO}/fleet-api" \
    "${TEST_REPO}/fleet-proxy" \
    "${TEST_REPO}/fleet-dash" \
    "${STUB_BIN}"
cp "${REPO_ROOT}/bin/local/deploy.sh" "${TEST_REPO}/bin/local/deploy.sh"

cat > "${STUB_BIN}/minikube" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "status" ]]; then
    printf 'Running\n'
elif [[ "${1:-}" == "docker-env" ]]; then
    printf ':\n'
fi
EOF

cat > "${STUB_BIN}/helm" <<'EOF'
#!/usr/bin/env bash
printf 'helm %s\n' "$*" >> "${COMMAND_LOG}"
exit 0
EOF

cat > "${STUB_BIN}/kubectl" <<'EOF'
#!/usr/bin/env bash
printf 'kubectl %s\n' "$*" >> "${COMMAND_LOG}"
if [[ "${1:-}" == "create" ]]; then
    printf 'apiVersion: v1\nkind: Namespace\n'
elif [[ "${1:-}" == "apply" ]]; then
    cat >/dev/null
elif [[ "${1:-}" == "get" ]]; then
    printf 'mock-fleet-component\n'
fi
EOF

cat > "${STUB_BIN}/git" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "diff" ]]; then
    printf '%s' "${GIT_CHANGED_PATHS:-}"
fi
EOF

cat > "${STUB_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "${COMMAND_LOG}"
EOF

for module in api proxy; do
    cat > "${TEST_REPO}/fleet-${module}/mvnw" <<'EOF'
#!/usr/bin/env bash
printf 'maven %s %s\n' "$(basename "$PWD")" "$*" >> "${COMMAND_LOG}"
EOF
    chmod +x "${TEST_REPO}/fleet-${module}/mvnw"
done

chmod +x "${STUB_BIN}"/* "${TEST_REPO}/bin/local/deploy.sh"

run_deploy() {
    local changed_paths="$1"
    local output

    if ! output=$(COMMAND_LOG="${COMMAND_LOG}" GIT_CHANGED_PATHS="${changed_paths}" \
        PATH="${STUB_BIN}:/usr/bin:/bin" \
        /bin/bash "${TEST_REPO}/bin/local/deploy.sh" 2>&1); then
        printf 'FAIL: deploy.sh must run under Bash %s\n%s\n' "${BASH_VERSION}" "${output}" >&2
        exit 1
    fi

    printf '%s\n' "${output}"
}

no_change_output=$(run_deploy '')
if [[ "${no_change_output}" != *'Modules selected for image rebuild: none.'* ]]; then
    printf 'FAIL: unchanged deployment did not preserve an empty module selection\n%s\n' "${no_change_output}" >&2
    exit 1
fi

: > "${COMMAND_LOG}"
api_change_output=$(run_deploy $'fleet-api/src/main/java/Example.java\n')
if [[ "${api_change_output}" != *'Modules selected for image rebuild: api.'* ]]; then
    printf 'FAIL: API change was not selected for rebuild\n%s\n' "${api_change_output}" >&2
    exit 1
fi

if ! grep -q '^maven fleet-api clean package -DskipTests$' "${COMMAND_LOG}"; then
    printf 'FAIL: API module was not built\n' >&2
    exit 1
fi

echo 'Local deploy Bash compatibility tests passed.'
