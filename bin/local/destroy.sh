#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
RELEASE_NAME=${RELEASE_NAME:-mock-fleet}
NAMESPACE=${MOCK_FLEET_NAMESPACE:-mock-fleet}
DELETE_NAMESPACE=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [--namespace <name>] [--release <name>] [--delete-namespace]

Destroy the local Minikube mock-fleet deployment.

Options:
  --namespace <name>     Kubernetes namespace to target. Defaults to ${NAMESPACE}.
  --release <name>       Helm release to uninstall. Defaults to ${RELEASE_NAME}.
  --delete-namespace     Delete the namespace after uninstalling the release.
  --help                 Show this help.
EOF
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

while [[ $# -gt 0 ]]; do
    case "$1" in
        --namespace)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --namespace." >&2
                usage >&2
                exit 1
            fi
            NAMESPACE="$2"
            shift 2
            ;;
        --release)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --release." >&2
                usage >&2
                exit 1
            fi
            RELEASE_NAME="$2"
            shift 2
            ;;
        --delete-namespace)
            DELETE_NAMESPACE=true
            shift
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

cd "${REPO_ROOT}"

echo "Checking Minikube status..."
require_minikube_running

if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
    echo "Namespace ${NAMESPACE} does not exist; nothing to destroy."
    exit 0
fi

echo "Uninstalling Helm release ${RELEASE_NAME} from namespace ${NAMESPACE}..."
helm uninstall "${RELEASE_NAME}" --ignore-not-found --namespace "${NAMESPACE}"

echo "Removing mock-fleet runtime pods and services in namespace ${NAMESPACE}..."
kubectl delete pod,service \
    --namespace "${NAMESPACE}" \
    -l app.kubernetes.io/managed-by=mock-fleet \
    --ignore-not-found

if [[ "${DELETE_NAMESPACE}" == "true" ]]; then
    echo "Deleting namespace ${NAMESPACE}..."
    kubectl delete namespace "${NAMESPACE}" --ignore-not-found
fi

echo "Destroyed local Minikube deployment for release ${RELEASE_NAME} in namespace ${NAMESPACE}."
