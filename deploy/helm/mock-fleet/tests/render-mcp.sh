#!/usr/bin/env bash
set -euo pipefail

chart_dir="${1:-deploy/helm/mock-fleet}"

disabled_render="$(helm template unusual-release "${chart_dir}" --namespace testing)"
if grep -Fq 'app.kubernetes.io/component: mcp' <<<"${disabled_render}"; then
  echo "MCP resources must be disabled by default" >&2
  exit 1
fi

enabled_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set fleet.mcp.enabled=true \
  --set clusterDomain=corp.internal \
  --set ingress.enabled=true \
  --set ingress.host=fleet.example.test \
  --set 'fleet.mcp.outbound.exceptions[0]=metadata.example' \
  --set 'fleet.mcp.outbound.allowedListeners[0]=webhook')"

role_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/role.yaml)"

api_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/api-deployment.yaml)"

proxy_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/proxy-deployment.yaml)"

dash_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/dash-deployment.yaml)"

mcp_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set fleet.mcp.enabled=true \
  --show-only templates/mcp-deployment.yaml)"

api_service_account_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set serviceAccount.annotations.identity=api \
  --show-only templates/serviceaccount.yaml)"

wiremock_service_account_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set wiremock.serviceAccount.annotations.identity=wiremock \
  --show-only templates/wiremock-serviceaccount.yaml)"

user_config_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/wiremock-user-configmap.yaml)"

version_catalog_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/wiremock-version-catalog-configmap.yaml)"

expected_configmap_rule=$'  - apiGroups:\n      - ""\n    resources:\n      - configmaps\n    resourceNames:\n      - custom-fleet-wiremock-user-config\n    verbs:\n      - get\n      - watch\n      - update\n      - patch'
if ! grep -Fq "${expected_configmap_rule}" <<<"${role_render}"; then
  echo "API role must limit user ConfigMap access to get/watch/update/patch by name" >&2
  exit 1
fi

if [[ "$(grep -Fc -- '- configmaps' <<<"${role_render}")" -ne 2 ]]; then
  echo "API role must have separate named user and version catalog ConfigMap rules" >&2
  exit 1
fi

configmap_rule_render="$(awk '/^      - configmaps$/{found=1} found{print}' <<<"${role_render}")"
for forbidden_verb in '- create' '- list' '- delete'; do
  if grep -Fq -- "${forbidden_verb}" <<<"${configmap_rule_render}"; then
    echo "API role must not grant ConfigMap verb: ${forbidden_verb}" >&2
    exit 1
  fi
done

expected_catalog_rule=$'  - apiGroups:\n      - ""\n    resources:\n      - configmaps\n    resourceNames:\n      - custom-fleet-wiremock-version-catalog\n    verbs:\n      - get\n      - watch'
if ! grep -Fq "${expected_catalog_rule}" <<<"${role_render}"; then
  echo "API role must limit version catalog access to named get/watch" >&2
  exit 1
fi

if grep -Fq -- '- deployments' <<<"${role_render}"; then
  echo "API role must not grant Deployment permissions" >&2
  exit 1
fi

expected_pod_rule=$'    resources:\n      - pods\n    verbs:\n      - get\n      - list\n      - create\n      - delete'
if ! grep -Fq "${expected_pod_rule}" <<<"${role_render}"; then
  echo "API role must retain only the required pod lifecycle permissions" >&2
  exit 1
fi

for workload in proxy dash mcp; do
  case "${workload}" in
    proxy) workload_render="${proxy_render}" ;;
    dash) workload_render="${dash_render}" ;;
    mcp) workload_render="${mcp_render}" ;;
  esac
  if ! grep -Fq 'automountServiceAccountToken: false' <<<"${workload_render}"; then
    echo "${workload} must not mount the Kubernetes API token" >&2
    exit 1
  fi
  if grep -Fq 'automountServiceAccountToken: true' <<<"${workload_render}"; then
    echo "${workload} must not opt back into the Kubernetes API token" >&2
    exit 1
  fi
done

for fragment in \
  'name: custom-fleet-wiremock-version-catalog' \
  'defaultVersion: 3.13.2' \
  'selectable.3.13.2: wiremock/wiremock:3.13.2-2' \
  'selectable.3.12.1: wiremock/wiremock:3.12.1-2' \
  'selectable.3.11.0: wiremock/wiremock:3.11.0-1' \
  'selectable.3.10.0: wiremock/wiremock:3.10.0-1' \
  'selectable.3.9.2: wiremock/wiremock:3.9.2-1'; do
  if ! grep -Fq "${fragment}" <<<"${version_catalog_render}"; then
    echo "WireMock version catalog is missing: ${fragment}" >&2
    exit 1
  fi
done

for fragment in \
  'name: MOCK_FLEET_WIREMOCK_VERSION_CATALOG_CONFIG_MAP_NAME' \
  'value: "custom-fleet-wiremock-version-catalog"'; do
  if ! grep -Fq "${fragment}" <<<"${api_render}"; then
    echo "API deployment is missing its named version catalog: ${fragment}" >&2
    exit 1
  fi
done

if ! grep -Fq 'automountServiceAccountToken: true' <<<"${api_render}"; then
  echo "API must retain its Kubernetes API token" >&2
  exit 1
fi
if grep -Fq 'automountServiceAccountToken: false' <<<"${api_render}"; then
  echo "API must not disable its required Kubernetes API token" >&2
  exit 1
fi

expected_api_strategy=$'  strategy:\n    type: Recreate'
if ! grep -Fq "${expected_api_strategy}" <<<"${api_render}"; then
  echo "API upgrades must replace the whole embedded Hazelcast cluster before starting new members" >&2
  exit 1
fi
if grep -Fq 'rollingUpdate:' <<<"${api_render}"; then
  echo "Default API upgrades must not mix incompatible Hazelcast member versions" >&2
  exit 1
fi

for workload in api proxy dash mcp; do
  case "${workload}" in
    api) workload_render="${api_render}" ;;
    proxy) workload_render="${proxy_render}" ;;
    dash) workload_render="${dash_render}" ;;
    mcp) workload_render="${mcp_render}" ;;
  esac
  expected_pod_context=$'      securityContext:\n        runAsNonRoot: true\n        seccompProfile:\n          type: RuntimeDefault'
  if ! grep -Fq "${expected_pod_context}" <<<"${workload_render}"; then
    echo "${workload} deployment is missing its restricted pod context" >&2
    exit 1
  fi
  expected_container_context=$'          securityContext:\n            runAsNonRoot: true\n            allowPrivilegeEscalation: false\n            capabilities:\n              drop:\n                - ALL\n            seccompProfile:\n              type: RuntimeDefault'
  if ! grep -Fq "${expected_container_context}" <<<"${workload_render}"; then
    echo "${workload} deployment is missing its restricted container context" >&2
    exit 1
  fi
  if grep -Fq 'readOnlyRootFilesystem: true' <<<"${workload_render}"; then
    echo "${workload} must retain its writable root filesystem" >&2
    exit 1
  fi
done

for fragment in \
  'name: custom-fleet-wiremock-user-config' \
  'helm.sh/resource-policy: keep' \
  'wiremock-options.yaml: |-' \
  'default:' \
  'options: []' \
  'mocks: []'; do
  if ! grep -Fq "${fragment}" <<<"${user_config_render}"; then
    echo "Release-owned user ConfigMap is missing: ${fragment}" >&2
    exit 1
  fi
done

for fragment in 'automountServiceAccountToken: true' 'identity: api'; do
  if ! grep -Fq "${fragment}" <<<"${api_service_account_render}"; then
    echo "API service account is missing: ${fragment}" >&2
    exit 1
  fi
done

for fragment in 'automountServiceAccountToken: false' 'identity: wiremock'; do
  if ! grep -Fq "${fragment}" <<<"${wiremock_service_account_render}"; then
    echo "WireMock service account is missing: ${fragment}" >&2
    exit 1
  fi
done

network_policy_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set fleet.mcp.enabled=true \
  --show-only templates/wiremock-egress-networkpolicy.yaml)"

storage_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set storage.persistent=true \
  --set storage.s3.bucket=contract-bucket \
  --show-only templates/wiremock-mappings-pv.yaml)"

for fragment in \
  '- allow-delete' \
  '- allow-overwrite' \
  '- metadata-ttl minimal'; do
  if ! grep -Fq -- "${fragment}" <<<"${storage_render}"; then
    echo "Persistent S3 storage is missing its multi-writer mount option: ${fragment}" >&2
    exit 1
  fi
done

required_fragments=(
  'name: custom-fleet-mcp'
  'type: Recreate'
  'replicas: 1'
  'automountServiceAccountToken: false'
  'value: "http://custom-fleet-api.testing.svc.corp.internal"'
  'value: "http://custom-fleet-proxy.testing.svc.corp.internal"'
  'value: "fleet.example.test"'
  'path: /__fleet/mcp'
  'image: ghcr.io/letsrokk/mock-fleet/mcp:1.5.1'
  'value: "metadata.example"'
  'value: "webhook"'
  'value: "50"'
  'value: "200"'
  'value: "1048576"'
  'value: "262144"'
  'value: "1S"'
  'value: "67108864"'
  'value: "100000"'
  'kind: NetworkPolicy'
  'cidr: 0.0.0.0/0'
  'cidr: 2000::/3'
  '2001:db8::/32'
)

for fragment in "${required_fragments[@]}"; do
  if ! grep -Fq "${fragment}" <<<"${enabled_render}"; then
    echo "Rendered MCP resources are missing: ${fragment}" >&2
    exit 1
  fi
done

for fragment in \
  'app.kubernetes.io/name: mock-fleet-wiremock' \
  'app.kubernetes.io/managed-by: mock-fleet'; do
  if ! grep -Fq "${fragment}" <<<"${network_policy_render}"; then
    echo "WireMock egress policy does not select upgrade-era managed pods: ${fragment}" >&2
    exit 1
  fi
done

network_policy_selector="$(awk '/^  podSelector:/{selector=1} selector{print} /^  policyTypes:/{exit}' <<<"${network_policy_render}")"
if grep -Fq 'app.kubernetes.io/instance:' <<<"${network_policy_selector}"; then
  echo "WireMock egress policy must also select pods created before release labels existed" >&2
  exit 1
fi

if grep -Fq 'cidr: ::/0' <<<"${network_policy_render}"; then
  echo "WireMock egress policy must not permit reserved IPv6 ranges outside global unicast" >&2
  exit 1
fi

long_name_render="$(helm template long-name "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijk \
  --set fleet.mcp.enabled=true \
  --show-only templates/wiremock-egress-networkpolicy.yaml)"
network_policy_name="$(awk '/^metadata:/{metadata=1; next} metadata && /^  name:/{print $2; exit}' <<<"${long_name_render}")"
if [[ -z "${network_policy_name}" || ${#network_policy_name} -gt 63 ]]; then
  echo "WireMock egress NetworkPolicy name exceeds 63 characters: ${network_policy_name}" >&2
  exit 1
fi

custom_port_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fleet.mcp.enabled=true \
  --set fleet.api.service.ports.http=8081 \
  --set fleet.proxy.service.ports.http=8082)"

for fragment in \
  'value: "http://mock-fleet-api.testing.svc.cluster.local:8081"' \
  'value: "http://mock-fleet-proxy.testing.svc.cluster.local:8082"'; do
  if ! grep -Fq "${fragment}" <<<"${custom_port_render}"; then
    echo "Rendered MCP service URL is missing its configured port: ${fragment}" >&2
    exit 1
  fi
done

grace_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set wiremock.terminationGracePeriodSeconds=12 \
  --show-only templates/api-deployment.yaml)"
grace_value="$(awk '/name: MOCK_FLEET_WIREMOCK_TERMINATION_GRACE_PERIOD_SECONDS/{getline; print $2; exit}' \
  <<<"${grace_render}")"
if [[ "${grace_value}" != '"12"' ]]; then
  echo "Rendered API deployment omitted the WireMock termination grace period: ${grace_value}" >&2
  exit 1
fi

mcp_ingress_line="$(grep -nE 'path: /__fleet/mcp$' <<<"${enabled_render}" | cut -d: -f1)"
dash_ingress_line="$(grep -nE 'path: /__fleet$' <<<"${enabled_render}" | cut -d: -f1)"
if [[ -z "${mcp_ingress_line}" || -z "${dash_ingress_line}" || ${mcp_ingress_line} -ge ${dash_ingress_line} ]]; then
  echo "MCP ingress route must precede the dashboard catch-all" >&2
  exit 1
fi

if grep -Fq '/internal/mocks/' <<<"${enabled_render}"; then
  echo "MCP must not use the Fleet API upstream resolver" >&2
  exit 1
fi

override_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fleet.mcp.enabled=true \
  --set fleet.mcp.apiBaseUrl=http://api.local:9000 \
  --set fleet.mcp.proxyBaseUrl=http://proxy.local:9001 \
  --set fleet.mcp.routing.mode=HOST \
  --set fleet.mcp.routing.fleetHost=internal.example.test \
  --set fleet.mcp.lifecycleTimeout=75S)"

for fragment in \
  'value: "http://api.local:9000"' \
  'value: "http://proxy.local:9001"' \
  'value: "HOST"' \
  'value: "internal.example.test"' \
  'value: "75S"'; do
  if ! grep -Fq "${fragment}" <<<"${override_render}"; then
    echo "Rendered MCP override is missing: ${fragment}" >&2
    exit 1
  fi
done

invalid_images=(
  wiremock/wiremock:latest
  wiremock/wiremock:2.35.1
  wiremock/wiremock@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  wiremock/wiremock:not-a-version
  wiremock/wiremock:3.13.2-custom
  wiremock/wiremock:4.0.0
)
for image in "${invalid_images[@]}"; do
  if helm template invalid "${chart_dir}" --set fleet.mcp.enabled=false --set "wiremock.containerImage=${image}" >/dev/null 2>&1; then
    echo "Releases must reject unsupported WireMock image ${image}" >&2
    exit 1
  fi
done

for image in wiremock/wiremock:3.0.0 wiremock/wiremock:3.13.2-2 wiremock/wiremock:3.14.0; do
  version="${image##*:}"
  helm template valid "${chart_dir}" --set fleet.mcp.enabled=false \
    --set "wiremock.containerImage=${image}" \
    --set-json "wiremock.supportedImageTags=[\"${version}\"]" >/dev/null
done


invalid_catalog_values=(
  '--set-json wiremock.supportedImageTags=[]'
  '--set-json wiremock.supportedImageTags=["3.13.2-2","3.13.2-1"]'
  '--set-json wiremock.supportedImageTags=["3.12.1-2"]'
  '--set-json wiremock.supportedImageTags=["latest"]'
  '--set-json wiremock.supportedImageTags=["3.13.2-alpine"]'
)
for arguments in "${invalid_catalog_values[@]}"; do
  read -r -a catalog_args <<<"${arguments}"
  if helm template invalid "${chart_dir}" "${catalog_args[@]}" >/dev/null 2>&1; then
    echo "Releases must reject invalid WireMock catalog values: ${arguments}" >&2
    exit 1
  fi
done
