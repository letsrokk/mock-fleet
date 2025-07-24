#!/usr/bin/env bash
# shellcheck disable=SC2046
set -e -o pipefail

delete_deployment() {
    helm uninstall mock-fleet --ignore-not-found
}

trap delete_deployment SIGINT

eval $(minikube docker-env)
quarkus build --no-tests -Dquarkus.profile=dev
helm dependency build target/helm/kubernetes/mock-fleet/
helm upgrade --install --force mock-fleet target/helm/kubernetes/mock-fleet/
kubectl wait --for=condition=Ready pod --timeout 1m -l app.kubernetes.io/name=mock-fleet
( kubectl port-forward service/mock-fleet 5005:5005 & )
kubectl logs -f -l app.kubernetes.io/name=mock-fleet