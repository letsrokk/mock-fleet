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
INGRESS_HOST=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--logs] [--port-forward] [--cleanup] [--ingress-host <host>] [--namespace <name>] [--profile <profiles>]

Deploy the generated Helm chart into Minikube using an in-cluster profile.

Options:
  --logs              Tail application logs after deployment.
  --port-forward      Forward service/${RELEASE_NAME} remote debug port 5005 to localhost:5005.
  --cleanup           Uninstall the Helm release before exiting.
  --ingress-host      Enable Ingress and bind it to the provided host, for example mock-fleet.localhost.
  --namespace <name>  Kubernetes namespace to use. Defaults to ${NAMESPACE}.
  --profile <value>   Quarkus profile(s) for packaging. Defaults to ${PROFILE}.
                      Use dev,local-k8s only when you intentionally need the
                      in-cluster app to run with local-debug port-forward logic.
  --help              Show this help.
EOF
}

build_ingress_patch_payload() {
    local release_name="$1"
    local ingress_host="$2"
    local bare_host="${ingress_host#*.}"

    if [[ "${ingress_host}" != \*.* ]]; then
        bare_host="${ingress_host}"
        ingress_host="*.${ingress_host}"
    fi

    printf '{"spec":{"rules":[{"host":"%s","http":{"paths":[{"backend":{"service":{"name":"%s","port":{"name":"http"}}},"path":"/","pathType":"Prefix"}]}},{"host":"%s","http":{"paths":[{"backend":{"service":{"name":"%s","port":{"name":"http"}}},"path":"/","pathType":"Prefix"}]}}]}}' \
        "${bare_host}" "${release_name}" "${ingress_host}" "${release_name}"
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
        --ingress-host)
            INGRESS_HOST="$2"
            shift 2
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

if [[ -n "${INGRESS_HOST}" ]]; then
    HELM_ARGS+=(--set app.ingress.enabled=true)
fi

helm "${HELM_ARGS[@]}"
kubectl wait --namespace "${NAMESPACE}" --for=condition=Ready pod --timeout=1m -l app.kubernetes.io/name=mock-fleet

if [[ -n "${INGRESS_HOST}" ]]; then
    INGRESS_PATCH_PAYLOAD=$(build_ingress_patch_payload "${RELEASE_NAME}" "${INGRESS_HOST}")
    kubectl patch ingress "${RELEASE_NAME}" --namespace "${NAMESPACE}" --type merge \
        -p "${INGRESS_PATCH_PAYLOAD}"
fi

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    kubectl port-forward --namespace "${NAMESPACE}" service/"${RELEASE_NAME}" 5005:5005 &
fi

if [[ "${ENABLE_LOGS}" == "true" ]]; then
    kubectl logs --namespace "${NAMESPACE}" -f -l app.kubernetes.io/name=mock-fleet
fi
