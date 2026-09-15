#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# Keep the installed controller version and its values, including the TLS store.
chart_version=$(helm list -n traefik -o json | python3 -c 'import json,sys; print(next(r["chart"].removeprefix("traefik-") for r in json.load(sys.stdin) if r["name"] == "traefik"))')
helm repo add traefik https://traefik.github.io/charts --force-update
helm repo update traefik
helm upgrade traefik traefik/traefik \
    --version "${chart_version}" -n traefik --reuse-values \
    -f "${REPO_ROOT}/deploy/helm/traefik/values.tinyproxy.minikube.yaml" --wait --timeout 2m
