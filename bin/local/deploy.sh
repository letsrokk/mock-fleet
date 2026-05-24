#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
RELEASE_NAME=${RELEASE_NAME:-mock-fleet}
NAMESPACE=${MOCK_FLEET_NAMESPACE:-mock-fleet}
ROUTING_MODE=${MOCK_FLEET_ROUTING_MODE:-}
CHART_DIR="${REPO_ROOT}/deploy/helm/mock-fleet"
MINIKUBE_VALUES_FILE="${CHART_DIR}/values.minikube.yaml"
LOCAL_PROXY_IMAGE="ghcr.io/letsrokk/mock-fleet-proxy:latest"
LOCAL_API_IMAGE="ghcr.io/letsrokk/mock-fleet-api:latest"
LOCAL_DASH_IMAGE="ghcr.io/letsrokk/mock-fleet-dash:latest"
REMOTE_DEV_MODULE=""
ENABLE_LOGS=false
ENABLE_PORT_FORWARD=false
CLEANUP=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--logs] [--port-forward] [--cleanup] [--namespace <name>] [--routing <HOST|PATH>] [--remote-dev <proxy|api>]

Deploy the hand-maintained Helm chart into Minikube.

Options:
  --logs              Tail application logs after deployment.
  --port-forward      Forward the selected remote-dev module debug port, or proxy debug port by default.
  --cleanup           Uninstall the Helm release before exiting.
  --namespace <name>  Kubernetes namespace to use. Defaults to ${NAMESPACE}.
  --routing <mode>    Override fleet.proxy.routing.mode from Helm values. Allowed: HOST, PATH.
  --remote-dev <module>
                      Enable Quarkus remote dev for one module. Allowed: proxy, api.
  --help              Show this help.
EOF
}

print_remote_dev_instructions() {
    local module="$1"

    echo
    echo "Remote dev follow-up:"
    echo "1. Start Quarkus remote dev:"
    echo "   cd ${REPO_ROOT}/fleet-${module} && ./mvnw quarkus:remote-dev -Dquarkus.profile=dev"
    echo "2. If live reload cannot reach the pod through ingress, expose the proxy service:"
    echo "   kubectl port-forward --namespace ${NAMESPACE} service/${RELEASE_NAME}-proxy 8080:80"
}

require_option_value() {
    local option="$1"
    local value="${2:-}"

    if [[ -z "${value}" || "${value}" == --* ]]; then
        echo "Missing value for ${option}." >&2
        usage >&2
        exit 1
    fi
}

has_module() {
    local wanted="$1"
    shift

    local module
    for module in "$@"; do
        if [[ "${module}" == "${wanted}" ]]; then
            return 0
        fi
    done

    return 1
}

join_modules() {
    local joined=""
    local module

    for module in "$@"; do
        if [[ -z "${joined}" ]]; then
            joined="${module}"
        else
            joined="${joined}, ${module}"
        fi
    done

    printf '%s\n' "${joined:-none}"
}

release_exists() {
    helm status "${RELEASE_NAME}" --namespace "${NAMESPACE}" >/dev/null 2>&1
}

mark_changed_modules() {
    local changed_modules=()
    local path

    while IFS= read -r path; do
        case "${path}" in
            fleet-proxy/*)
                if ! has_module proxy "${changed_modules[@]}"; then
                    changed_modules+=(proxy)
                fi
                ;;
            fleet-api/*)
                if ! has_module api "${changed_modules[@]}"; then
                    changed_modules+=(api)
                fi
                ;;
            fleet-dash/*)
                if ! has_module dash "${changed_modules[@]}"; then
                    changed_modules+=(dash)
                fi
                ;;
        esac
    done < <(
        {
            git diff --name-only HEAD
            git ls-files --others --exclude-standard
        } | sort -u
    )

    printf '%s\n' "${changed_modules[@]}"
}

build_maven_module() {
    local module="$1"

    echo "Packaging ${module} application and building image via Maven..."
    (
        cd "${REPO_ROOT}/fleet-${module}"
        ./mvnw "${MAVEN_ARGS[@]}"
    )
}

deployment_name_for_component() {
    local component="$1"

    kubectl get deployment \
        --namespace "${NAMESPACE}" \
        -l "app.kubernetes.io/name=mock-fleet,app.kubernetes.io/component=${component}" \
        -o jsonpath='{.items[0].metadata.name}'
}

rollout_component() {
    local component="$1"
    local deployment_name="$2"

    if [[ -z "${deployment_name}" ]]; then
        echo "No mock-fleet ${component} deployment found in namespace ${NAMESPACE} after Helm upgrade." >&2
        exit 1
    fi

    echo "Restarting ${component} deployment to pick up the refreshed local image..."
    kubectl rollout restart --namespace "${NAMESPACE}" "deployment/${deployment_name}"
    kubectl rollout status --namespace "${NAMESPACE}" "deployment/${deployment_name}" --timeout=1m
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --logs)
            ENABLE_LOGS=true
            shift
            ;;
        --port-forward)
            ENABLE_PORT_FORWARD=true
            shift
            ;;
        --cleanup)
            CLEANUP=true
            shift
            ;;
        --namespace)
            require_option_value "$1" "${2:-}"
            NAMESPACE="$2"
            shift 2
            ;;
        --routing|--routing-mode)
            require_option_value "$1" "${2:-}"
            ROUTING_MODE="$2"
            shift 2
            ;;
        --remote-dev)
            require_option_value "$1" "${2:-}"
            REMOTE_DEV_MODULE="$2"
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -n "${ROUTING_MODE}" && "${ROUTING_MODE}" != "HOST" && "${ROUTING_MODE}" != "PATH" ]]; then
    echo "Invalid routing mode: ${ROUTING_MODE}. Expected HOST or PATH." >&2
    usage >&2
    exit 1
fi

if [[ -n "${REMOTE_DEV_MODULE}" && "${REMOTE_DEV_MODULE}" != "proxy" && "${REMOTE_DEV_MODULE}" != "api" ]]; then
    echo "Invalid remote dev module: ${REMOTE_DEV_MODULE}. Expected proxy or api." >&2
    usage >&2
    exit 1
fi

cleanup() {
    if [[ "${CLEANUP}" == "true" ]]; then
        helm uninstall "${RELEASE_NAME}" --ignore-not-found --namespace "${NAMESPACE}"
    fi
}

require_minikube_running() {
    if ! command -v minikube >/dev/null 2>&1; then
        echo "Minikube is not installed or not available on PATH." >&2
        exit 1
    fi

    local host_status
    if ! host_status=$(minikube status --format='{{.Host}}' 2>/dev/null); then
        echo "Minikube is not running. Start it first with: minikube start" >&2
        exit 1
    fi

    if [[ "${host_status}" != "Running" ]]; then
        echo "Minikube is not running. Start it first with: minikube start" >&2
        exit 1
    fi
}

use_minikube_docker_daemon() {
    eval "$(minikube docker-env --shell bash)"
}

reset_docker_daemon() {
    eval "$(minikube docker-env --shell bash --unset)"
}

trap cleanup EXIT

cd "${REPO_ROOT}"

echo "Checking Minikube status..."
require_minikube_running

CHANGED_MODULES=()
if ! release_exists; then
    CHANGED_MODULES=(proxy api dash)
    echo "Helm release ${RELEASE_NAME} is not installed in namespace ${NAMESPACE}; building all module images."
else
    mapfile -t CHANGED_MODULES < <(mark_changed_modules)
fi

if [[ -n "${REMOTE_DEV_MODULE}" ]] && ! has_module "${REMOTE_DEV_MODULE}" "${CHANGED_MODULES[@]}"; then
    CHANGED_MODULES+=("${REMOTE_DEV_MODULE}")
fi

echo "Modules selected for image rebuild: $(join_modules "${CHANGED_MODULES[@]}")."

MAVEN_ARGS=(
    clean package
    -DskipTests
)

if [[ -n "${REMOTE_DEV_MODULE}" ]]; then
    MAVEN_ARGS+=("-Dquarkus.profile=dev")
fi

if [[ ${#CHANGED_MODULES[@]} -gt 0 ]]; then
    echo "Pointing Docker commands at the Minikube daemon..."
    use_minikube_docker_daemon

    if has_module proxy "${CHANGED_MODULES[@]}"; then
        build_maven_module proxy
    fi

    if has_module api "${CHANGED_MODULES[@]}"; then
        build_maven_module api
    fi

    if has_module dash "${CHANGED_MODULES[@]}"; then
        echo "Building dashboard image..."
        docker build -t "${LOCAL_DASH_IMAGE}" "${REPO_ROOT}/fleet-dash"
    fi

    echo "Resetting Docker commands back to the host daemon..."
    reset_docker_daemon
else
    echo "No module image rebuild needed."
fi

helm dependency build "${CHART_DIR}"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
HELM_ARGS=(
    upgrade --install "${RELEASE_NAME}" "${CHART_DIR}"
    --namespace "${NAMESPACE}"
    --create-namespace
    -f "${CHART_DIR}/values.yaml"
    -f "${MINIKUBE_VALUES_FILE}"
    --set "fleet.proxy.image.repository=ghcr.io/letsrokk/mock-fleet-proxy"
    --set "fleet.proxy.image.tag=latest"
    --set "fleet.api.image.repository=ghcr.io/letsrokk/mock-fleet-api"
    --set "fleet.api.image.tag=latest"
    --set "fleet.dash.image.repository=ghcr.io/letsrokk/mock-fleet-dash"
    --set "fleet.dash.image.tag=latest"
)

if [[ "${REMOTE_DEV_MODULE}" == "proxy" ]]; then
    HELM_ARGS+=(--set "fleet.proxy.dev.enabled=true")
elif [[ "${REMOTE_DEV_MODULE}" == "api" ]]; then
    HELM_ARGS+=(--set "fleet.api.dev.enabled=true")
fi

if [[ -n "${ROUTING_MODE}" ]]; then
    HELM_ARGS+=(--set "fleet.proxy.routing.mode=${ROUTING_MODE}")
    routing_message="fleet.proxy.routing.mode override=${ROUTING_MODE}"
else
    routing_message="fleet.proxy.routing.mode from Helm values"
fi

if [[ -n "${REMOTE_DEV_MODULE}" ]]; then
    profile_message="Quarkus profile=dev"
else
    profile_message="default Quarkus profile"
fi

echo "Deploying ${RELEASE_NAME} to namespace ${NAMESPACE} with proxy image=${LOCAL_PROXY_IMAGE}, API image=${LOCAL_API_IMAGE}, dashboard image=${LOCAL_DASH_IMAGE}, ${routing_message}, ${profile_message}, and Minikube values from ${MINIKUBE_VALUES_FILE}."
helm "${HELM_ARGS[@]}"

if has_module proxy "${CHANGED_MODULES[@]}"; then
    rollout_component proxy "$(deployment_name_for_component proxy)"
fi

if has_module api "${CHANGED_MODULES[@]}"; then
    rollout_component api "$(deployment_name_for_component api)"
fi

if has_module dash "${CHANGED_MODULES[@]}"; then
    rollout_component dash "$(deployment_name_for_component dash)"
fi

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    if [[ "${REMOTE_DEV_MODULE}" == "api" ]]; then
        kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}-api" 5005:5006 &
    else
        kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}-proxy" 5005:5005 &
    fi
fi

if [[ -n "${REMOTE_DEV_MODULE}" ]]; then
    print_remote_dev_instructions "${REMOTE_DEV_MODULE}"
fi

if [[ "${ENABLE_LOGS}" == "true" ]]; then
    kubectl logs --namespace "${NAMESPACE}" --follow=true --max-log-requests=7 --selector=app.kubernetes.io/name=mock-fleet
fi
