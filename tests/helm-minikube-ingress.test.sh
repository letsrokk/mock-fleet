#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CHART_DIR="${REPO_ROOT}/deploy/helm/mock-fleet"
VALUES_FILE="${CHART_DIR}/values.minikube.yaml"

assert_contains() {
    local expected="$1"
    local actual="$2"
    local description="$3"

    if [[ "${actual}" != *"${expected}"* ]]; then
        printf 'FAIL: %s\nMissing: %s\n' "${description}" "${expected}" >&2
        exit 1
    fi
}

assert_not_contains() {
    local unexpected="$1"
    local actual="$2"
    local description="$3"

    if [[ "${actual}" == *"${unexpected}"* ]]; then
        printf 'FAIL: %s\nUnexpected: %s\n' "${description}" "${unexpected}" >&2
        exit 1
    fi
}

rendered=$(helm template mock-fleet "${CHART_DIR}" --namespace mock-fleet -f "${VALUES_FILE}")

assert_contains 'ingressClassName: traefik' "${rendered}" 'Minikube ingress uses Traefik'
assert_contains 'host: "mock-fleet.minikube.localhost"' "${rendered}" 'Minikube ingress uses the dedicated hostname'
assert_contains 'traefik.ingress.kubernetes.io/router.entrypoints: websecure' "${rendered}" 'Minikube ingress uses the HTTPS entrypoint'
assert_contains 'traefik.ingress.kubernetes.io/router.tls: "true"' "${rendered}" 'Minikube ingress enables Traefik TLS'
assert_contains 'name: MOCK_FLEET_ROUTING_MODE
              value: "PATH"' "${rendered}" 'Proxy deployment uses path routing'
assert_contains 'name: MOCK_FLEET_ROUTING_HOST
              value: "mock-fleet.minikube.localhost"' "${rendered}" 'Proxy deployment receives the Minikube host'
assert_contains 'name: MOCK_FLEET_PROXY_ROUTING_HOST
              value: "mock-fleet.minikube.localhost"' "${rendered}" 'API deployment receives the Minikube host'
assert_not_contains 'host: "*.mock-fleet.minikube.localhost"' "${rendered}" 'Path routing omits the wildcard ingress rule'

echo 'Helm Minikube ingress tests passed.'
