#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
RELEASE_NAME=${RELEASE_NAME:-mock-fleet}
NAMESPACE=${MOCK_FLEET_NAMESPACE:-mock-fleet}
PROFILE=${QUARKUS_PROFILE:-dev}
DEFAULT_ROUTING_MODE=HOST
ROUTING_MODE=${MOCK_FLEET_ROUTING_MODE:-${DEFAULT_ROUTING_MODE}}
CHART_DIR="${REPO_ROOT}/deploy/helm/mock-fleet"
MINIKUBE_VALUES_FILE="${CHART_DIR}/values.minikube.yaml"
LOCAL_IMAGE="ghcr.io/letsrokk/mock-fleet:latest"
ENABLE_LOGS=false
ENABLE_PORT_FORWARD=false
CLEANUP=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--logs] [--port-forward] [--cleanup] [--namespace <name>] [--profile <profile>] [--routing <HOST|PATH>]

Deploy the hand-maintained Helm chart into Minikube using an in-cluster profile.

Options:
  --logs              Tail application logs after deployment.
  --port-forward      Forward service/${RELEASE_NAME} remote debug port 5005 to localhost:5005.
  --cleanup           Uninstall the Helm release before exiting.
  --namespace <name>  Kubernetes namespace to use. Defaults to ${NAMESPACE}.
  --profile <value>   Quarkus profile for packaging. Defaults to ${PROFILE}.
  --routing <mode>    Routing mode to deploy. Allowed: HOST, PATH. Defaults to ${DEFAULT_ROUTING_MODE}.
  --help              Show this help.
EOF
}

print_remote_dev_instructions() {
    local release_name="$1"
    local namespace="$2"
    local profile="$3"

    echo
    echo "Remote dev follow-up:"
    if [[ "${profile}" == "dev" ]]; then
        echo "1. In another terminal, start Quarkus remote dev:"
        echo "   ./mvnw quarkus:remote-dev -Dquarkus.profile=${profile}"
    else
        live_reload_url="http://127.0.0.1:8080"
        echo "1. In a separate terminal, expose the app:"
        echo "   kubectl port-forward --namespace ${namespace} service/${release_name} 8080:8080"
        echo "2. In another terminal, start Quarkus remote dev:"
        echo "   ./mvnw quarkus:remote-dev -Dquarkus.profile=${profile} -Dquarkus.live-reload.url=${live_reload_url}"
    fi
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
            NAMESPACE="$2"
            shift 2
            ;;
        --profile)
            PROFILE="$2"
            shift 2
            ;;
        --routing|--routing-mode)
            ROUTING_MODE="$2"
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

if [[ "${ROUTING_MODE}" != "HOST" && "${ROUTING_MODE}" != "PATH" ]]; then
    echo "Invalid routing mode: ${ROUTING_MODE}. Expected HOST or PATH." >&2
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

trap cleanup EXIT

cd "${REPO_ROOT}"

echo "Checking Minikube status..."
require_minikube_running

echo "Packaging application and building image via Maven..."
./mvnw package -DskipTests "-Dquarkus.profile=${PROFILE}"

echo "Loading ${LOCAL_IMAGE} into Minikube..."
minikube image load "${LOCAL_IMAGE}"

helm dependency build "${CHART_DIR}"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
HELM_ARGS=(
    upgrade --install "${RELEASE_NAME}" "${CHART_DIR}"
    --namespace "${NAMESPACE}"
    --create-namespace
    -f "${CHART_DIR}/values.yaml"
    -f "${MINIKUBE_VALUES_FILE}"
    --set "routing.mode=${ROUTING_MODE}"
)

echo "Deploying ${RELEASE_NAME} to namespace ${NAMESPACE} with image=${LOCAL_IMAGE}, routing.mode=${ROUTING_MODE}, profile=${PROFILE}, and Minikube values from ${MINIKUBE_VALUES_FILE}."
helm "${HELM_ARGS[@]}"
kubectl wait --namespace "${NAMESPACE}" --for=condition=Ready pod --timeout=1m -l app.kubernetes.io/name=mock-fleet

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}" 5005:5005 &
fi

print_remote_dev_instructions "${RELEASE_NAME}" "${NAMESPACE}" "${PROFILE}"

if [[ "${ENABLE_LOGS}" == "true" ]]; then
    kubectl logs --namespace "${NAMESPACE}" -f -l app.kubernetes.io/name=mock-fleet
fi
