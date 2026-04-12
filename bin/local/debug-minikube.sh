#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
RELEASE_NAME=${RELEASE_NAME:-mock-fleet}
NAMESPACE=${MOCK_FLEET_NAMESPACE:-mock-fleet}
PROFILE=${QUARKUS_PROFILE:-dev}
CHART_DIR="${REPO_ROOT}/target/helm/kubernetes/mock-fleet"
ENABLE_LOGS=false
ENABLE_PORT_FORWARD=false
CLEANUP=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--logs] [--port-forward] [--cleanup] [--namespace <name>] [--profile <profile>]

Deploy the generated Helm chart into Minikube using an in-cluster profile.

Options:
  --logs              Tail application logs after deployment.
  --port-forward      Forward service/${RELEASE_NAME} remote debug port 5005 to localhost:5005.
  --cleanup           Uninstall the Helm release before exiting.
  --namespace <name>  Kubernetes namespace to use. Defaults to ${NAMESPACE}.
  --profile <value>   Quarkus profile for packaging. Defaults to ${PROFILE}.
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

cleanup() {
    if [[ "${CLEANUP}" == "true" ]]; then
        helm uninstall "${RELEASE_NAME}" --ignore-not-found --namespace "${NAMESPACE}"
    fi
}

trap cleanup EXIT

cd "${REPO_ROOT}"

eval "$(minikube docker-env)"
./mvnw package -DskipTests "-Dquarkus.profile=${PROFILE}"
helm dependency build "${CHART_DIR}"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
HELM_ARGS=(
    upgrade --install "${RELEASE_NAME}" "${CHART_DIR}"
    --namespace "${NAMESPACE}"
    --create-namespace
)

helm "${HELM_ARGS[@]}"
kubectl wait --namespace "${NAMESPACE}" --for=condition=Ready pod --timeout=1m -l app.kubernetes.io/name=mock-fleet

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}" 5005:5005 &
fi

print_remote_dev_instructions "${RELEASE_NAME}" "${NAMESPACE}" "${PROFILE}"

if [[ "${ENABLE_LOGS}" == "true" ]]; then
    kubectl logs --namespace "${NAMESPACE}" -f -l app.kubernetes.io/name=mock-fleet
fi
