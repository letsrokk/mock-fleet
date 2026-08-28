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

network_policy_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set fleet.mcp.enabled=true \
  --show-only templates/wiremock-egress-networkpolicy.yaml)"

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
  --set fleet.mcp.routing.fleetHost=internal.example.test)"

for fragment in \
  'value: "http://api.local:9000"' \
  'value: "http://proxy.local:9001"' \
  'value: "HOST"' \
  'value: "internal.example.test"'; do
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
)
for image in "${invalid_images[@]}"; do
  if helm template invalid "${chart_dir}" --set fleet.mcp.enabled=true --set "wiremock.containerImage=${image}" >/dev/null 2>&1; then
    echo "MCP-enabled releases must reject unsupported WireMock image ${image}" >&2
    exit 1
  fi
done
