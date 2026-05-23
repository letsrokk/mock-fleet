#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
RELEASE_NAME=${RELEASE_NAME:-mock-fleet}
NAMESPACE=${MOCK_FLEET_NAMESPACE:-mock-fleet}
PROFILE=${QUARKUS_PROFILE:-prod}
ROUTING_MODE=${MOCK_FLEET_ROUTING_MODE:-}
CHART_DIR="${REPO_ROOT}/deploy/helm/mock-fleet"
MINIKUBE_VALUES_FILE="${CHART_DIR}/values.minikube.yaml"
LOCAL_PROXY_IMAGE="ghcr.io/letsrokk/mock-fleet-proxy:latest"
LOCAL_API_IMAGE="ghcr.io/letsrokk/mock-fleet-api:latest"
LOCAL_DASH_IMAGE="ghcr.io/letsrokk/mock-fleet-dash:latest"
ENABLE_LOGS=false
ENABLE_PORT_FORWARD=false
CLEANUP=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--logs] [--port-forward] [--cleanup] [--namespace <name>] [--profile <profile>] [--routing <HOST|PATH>]

Deploy the hand-maintained Helm chart into Minikube.

Options:
  --logs              Tail application logs after deployment.
  --port-forward      Forward service/${RELEASE_NAME} remote debug port 5005 to localhost:5005.
  --cleanup           Uninstall the Helm release before exiting.
  --namespace <name>  Kubernetes namespace to use. Defaults to ${NAMESPACE}.
  --profile <value>   Quarkus profile for packaging. Defaults to ${PROFILE}. Use prod for Kubernetes probes on port 8080.
  --routing <mode>    Override fleet.proxy.routing.mode from Helm values. Allowed: HOST, PATH.
  --help              Show this help.
EOF
}

print_follow_up_instructions() {
    local release_name="$1"
    local namespace="$2"

    echo
    echo "Follow-up:"
    echo "1. Expose the proxy service if needed:"
    echo "   kubectl port-forward --namespace ${namespace} service/${release_name}-proxy 8080:80"
    echo "2. Open:"
    echo "   http://127.0.0.1:8080/__fleet/"
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

if [[ -n "${ROUTING_MODE}" && "${ROUTING_MODE}" != "HOST" && "${ROUTING_MODE}" != "PATH" ]]; then
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

echo "Pointing Docker commands at the Minikube daemon..."
use_minikube_docker_daemon

MAVEN_ARGS=(
    clean package
    -DskipTests
)

if [[ "${PROFILE}" != "prod" ]]; then
    MAVEN_ARGS+=("-Dquarkus.profile=${PROFILE}")
fi

echo "Packaging proxy application and building image via Maven..."
(
    cd "${REPO_ROOT}/fleet-proxy"
    ./mvnw "${MAVEN_ARGS[@]}"
)

echo "Packaging API application and building image via Maven..."
(
    cd "${REPO_ROOT}/fleet-api"
    ./mvnw "${MAVEN_ARGS[@]}"
)

echo "Building dashboard image..."
docker build -t "${LOCAL_DASH_IMAGE}" "${REPO_ROOT}/fleet-dash"

echo "Resetting Docker commands back to the host daemon..."
reset_docker_daemon

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

if [[ -n "${ROUTING_MODE}" ]]; then
    HELM_ARGS+=(--set "fleet.proxy.routing.mode=${ROUTING_MODE}")
    routing_message="fleet.proxy.routing.mode override=${ROUTING_MODE}"
else
    routing_message="fleet.proxy.routing.mode from Helm values"
fi

echo "Deploying ${RELEASE_NAME} to namespace ${NAMESPACE} with proxy image=${LOCAL_PROXY_IMAGE}, API image=${LOCAL_API_IMAGE}, dashboard image=${LOCAL_DASH_IMAGE}, ${routing_message}, profile=${PROFILE}, and Minikube values from ${MINIKUBE_VALUES_FILE}."
helm "${HELM_ARGS[@]}"

proxy_deployment_name=$(
  kubectl get deployment \
    --namespace "${NAMESPACE}" \
    -l app.kubernetes.io/name=mock-fleet,app.kubernetes.io/component=proxy \
    -o jsonpath='{.items[0].metadata.name}'
)

api_deployment_name=$(
  kubectl get deployment \
    --namespace "${NAMESPACE}" \
    -l app.kubernetes.io/name=mock-fleet,app.kubernetes.io/component=api \
    -o jsonpath='{.items[0].metadata.name}'
)

dash_deployment_name=$(
  kubectl get deployment \
    --namespace "${NAMESPACE}" \
    -l app.kubernetes.io/name=mock-fleet,app.kubernetes.io/component=dash \
    -o jsonpath='{.items[0].metadata.name}'
)

if [[ -z "${proxy_deployment_name}" ]]; then
  echo "No mock-fleet proxy deployment found in namespace ${NAMESPACE} after Helm upgrade." >&2
  exit 1
fi

if [[ -z "${api_deployment_name}" ]]; then
  echo "No mock-fleet API deployment found in namespace ${NAMESPACE} after Helm upgrade." >&2
  exit 1
fi

if [[ -z "${dash_deployment_name}" ]]; then
  echo "No mock-fleet dashboard deployment found in namespace ${NAMESPACE} after Helm upgrade." >&2
  exit 1
fi

echo "Restarting deployments to pick up the refreshed local images..."
kubectl rollout restart --namespace "${NAMESPACE}" "deployment/${proxy_deployment_name}" "deployment/${api_deployment_name}" "deployment/${dash_deployment_name}"

kubectl rollout status \
  --namespace "${NAMESPACE}" \
  "deployment/${proxy_deployment_name}" \
  --timeout=1m

kubectl rollout status \
  --namespace "${NAMESPACE}" \
  "deployment/${api_deployment_name}" \
  --timeout=1m

kubectl rollout status \
  --namespace "${NAMESPACE}" \
  "deployment/${dash_deployment_name}" \
  --timeout=1m

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}-proxy" 5005:5005 &
fi

print_follow_up_instructions "${RELEASE_NAME}" "${NAMESPACE}"

if [[ "${ENABLE_LOGS}" == "true" ]]; then
    kubectl logs --namespace "${NAMESPACE}" --follow=true --max-log-requests=7 --selector=app.kubernetes.io/name=mock-fleet
fi
