#!/usr/bin/env bash
set -euo pipefail

chart_dir="${1:-deploy/helm/mock-fleet}"

fail() {
  echo "$1" >&2
  exit 1
}

expect_render_failure() {
  local description="$1"
  local expected="$2"
  shift 2
  local output
  if output="$(helm template invalid "${chart_dir}" "$@" 2>&1)"; then
    fail "Invalid values rendered successfully: ${description}"
  fi
  if ! grep -Fq "${expected}" <<<"${output}"; then
    echo "Unexpected validation output for ${description}:" >&2
    echo "${output}" >&2
    exit 1
  fi
}

default_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet)"

egress_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set fleet.mcp.enabled=false \
  --set 'fleet.mcp.outbound.networkPolicy.allowedCidrs[0]=203.0.114.0/24' \
  --show-only templates/wiremock-egress-networkpolicy.yaml)"

for fragment in \
  'kind: NetworkPolicy' \
  'name: custom-fleet-wiremock-egress' \
  'app.kubernetes.io/name: mock-fleet-wiremock' \
  'app.kubernetes.io/managed-by: mock-fleet' \
  'kubernetes.io/metadata.name: "kube-system"' \
  'k8s-app: kube-dns' \
  'protocol: UDP' \
  'protocol: TCP' \
  'port: 53' \
  'cidr: 0.0.0.0/0' \
  'cidr: 2000::/3' \
  'cidr: "203.0.114.0/24"' \
  '10.0.0.0/8' \
  '169.254.0.0/16' \
  '172.16.0.0/12' \
  '192.168.0.0/16'; do
  grep -Fq "${fragment}" <<<"${egress_render}" || fail "WireMock egress policy is missing: ${fragment}"
done

egress_selector="$(awk '/^  podSelector:/{selected=1} selected{print} /^  policyTypes:/{exit}' <<<"${egress_render}")"
expected_egress_selector=$'  podSelector:\n    matchLabels:\n      app.kubernetes.io/name: mock-fleet-wiremock\n      app.kubernetes.io/managed-by: mock-fleet\n  policyTypes:'
[[ "${egress_selector}" == "${expected_egress_selector}" ]] || fail "WireMock egress policy must use only the exact stable managed-pod labels"

for forbidden_allow in 'cidr: 10.0.0.0/8' 'cidr: 169.254.0.0/16' 'cidr: 172.16.0.0/12' 'cidr: 192.168.0.0/16'; do
  if grep -Fq "    ${forbidden_allow}" <<<"${egress_render}"; then
    fail "WireMock egress policy must not allow a private or cluster range directly: ${forbidden_allow}"
  fi
done

api_policy_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/api-networkpolicy.yaml)"

for fragment in \
  'kind: NetworkPolicy' \
  'name: custom-fleet-api-ingress' \
  'app.kubernetes.io/name: mock-fleet' \
  'app.kubernetes.io/instance: unusual-release' \
  'app.kubernetes.io/component: api' \
  'port: 8080' \
  'port: 5701'; do
  grep -Fq "${fragment}" <<<"${api_policy_render}" || fail "API ingress policy is missing: ${fragment}"
done

[[ "$(grep -Fc 'port: 5701' <<<"${api_policy_render}")" -eq 1 ]] || fail "API ingress policy must open Hazelcast exactly once"
hazelcast_rule="$(awk '/    - from:/{rule=1} rule{print} /port: 5701/{exit}' <<<"${api_policy_render}")"
for fragment in \
  'app.kubernetes.io/name: mock-fleet' \
  'app.kubernetes.io/instance: unusual-release' \
  'app.kubernetes.io/component: api' \
  'protocol: TCP' \
  'port: 5701'; do
  grep -Fq "${fragment}" <<<"${hazelcast_rule}" || fail "Hazelcast ingress is not restricted to same-release API pods: ${fragment}"
done

api_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/api-deployment.yaml)"

expected_default_env=(
  'MOCK_FLEET_MAX_ACTIVE_MOCKS="20"'
  'MOCK_FLEET_MAX_CONCURRENT_STARTS="4"'
  'MOCK_FLEET_QUEUED_START_CAPACITY="16"'
  'MOCK_FLEET_MAPPINGS_MAX_DEPTH="32"'
  'MOCK_FLEET_MAPPINGS_MAX_ENTRIES="10000"'
  'MOCK_FLEET_WIREMOCK_RESOURCE_REQUEST_FLOOR_CPU="100m"'
  'MOCK_FLEET_WIREMOCK_RESOURCE_REQUEST_FLOOR_MEMORY="128Mi"'
  'MOCK_FLEET_WIREMOCK_RESOURCE_LIMIT_CEILING_CPU="4"'
  'MOCK_FLEET_WIREMOCK_RESOURCE_LIMIT_CEILING_MEMORY="4Gi"'
)
for assignment in "${expected_default_env[@]}"; do
  name="${assignment%%=*}"
  value="${assignment#*=}"
  rendered_value="$(awk -v name="${name}" '$0 ~ "name: " name "$" {getline; print $2; exit}' <<<"${api_render}")"
  [[ "${rendered_value}" == "${value}" ]] || fail "API default ${name} rendered as ${rendered_value}, expected ${value}"
done

custom_api_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fleet.api.maxActiveMocks=12 \
  --set fleet.api.maxConcurrentStarts=3 \
  --set fleet.api.queuedStartCapacity=9 \
  --set fleet.api.mappings.maxDepth=16 \
  --set fleet.api.mappings.maxEntries=2048 \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=250m \
  --set-string wiremock.resourcePolicy.requestFloor.memory=256Mi \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=2 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=2Gi \
  --set-string wiremock.config.default.resources.requests.cpu=250m \
  --set-string wiremock.config.default.resources.requests.memory=256Mi \
  --set-string wiremock.config.default.resources.limits.cpu=2 \
  --set-string wiremock.config.default.resources.limits.memory=2Gi \
  --show-only templates/api-deployment.yaml)"

for value in '"12"' '"3"' '"9"' '"16"' '"2048"' '"250m"' '"256Mi"' '"2"' '"2Gi"'; do
  grep -Fq "value: ${value}" <<<"${custom_api_render}" || fail "Custom API security value did not render: ${value}"
done

quota_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/resourcequota.yaml)"
for fragment in \
  'kind: ResourceQuota' \
  'name: custom-fleet' \
  'pods: 30' \
  'requests.cpu: "8"' \
  'requests.memory: 12Gi' \
  'limits.cpu: "16"' \
  'limits.memory: 24Gi'; do
  grep -Fq "${fragment}" <<<"${quota_render}" || fail "ResourceQuota default is missing: ${fragment}"
done

disabled_quota="$(helm template unusual-release "${chart_dir}" --namespace testing --set resourceQuota.enabled=false)"
if grep -Fq 'kind: ResourceQuota' <<<"${disabled_quota}"; then
  fail "ResourceQuota must render only when resourceQuota.enabled=true"
fi

expect_render_failure 'zero maxActiveMocks' '/fleet/api/maxActiveMocks' --set fleet.api.maxActiveMocks=0
expect_render_failure 'zero maxConcurrentStarts' '/fleet/api/maxConcurrentStarts' --set fleet.api.maxConcurrentStarts=0
expect_render_failure 'zero queuedStartCapacity' '/fleet/api/queuedStartCapacity' --set fleet.api.queuedStartCapacity=0
expect_render_failure 'zero mappings maxDepth' '/fleet/api/mappings/maxDepth' --set fleet.api.mappings.maxDepth=0
expect_render_failure 'zero mappings maxEntries' '/fleet/api/mappings/maxEntries' --set fleet.api.mappings.maxEntries=0
expect_render_failure 'zero quota pod count' '/resourceQuota/hard/pods' --set resourceQuota.hard.pods=0
expect_render_failure 'concurrent starts above active mocks' 'fleet.api.maxConcurrentStarts must not exceed fleet.api.maxActiveMocks' \
  --set fleet.api.maxActiveMocks=2 --set fleet.api.maxConcurrentStarts=3
expect_render_failure 'CPU floor above ceiling' 'wiremock.resourcePolicy.requestFloor.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu' \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=2 --set-string wiremock.resourcePolicy.limitCeiling.cpu=1
expect_render_failure 'memory baseline request below floor' 'wiremock.config.default.resources.requests.memory must not be below wiremock.resourcePolicy.requestFloor.memory' \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Gi --set-string wiremock.config.default.resources.requests.memory=512Mi
expect_render_failure 'CPU baseline limit above ceiling' 'wiremock.config.default.resources.limits.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu' \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=500m --set-string wiremock.config.default.resources.limits.cpu=1
expect_render_failure 'memory baseline request above limit' 'wiremock.config.default.resources.requests.memory must not exceed wiremock.config.default.resources.limits.memory' \
  --set-string wiremock.config.default.resources.requests.memory=2Gi --set-string wiremock.config.default.resources.limits.memory=1Gi

for invariant in \
  'kind: ResourceQuota' \
  'name: MOCK_FLEET_MAX_ACTIVE_MOCKS' \
  'name: MOCK_FLEET_MAPPINGS_MAX_DEPTH' \
  'name: MOCK_FLEET_WIREMOCK_RESOURCE_LIMIT_CEILING_MEMORY'; do
  grep -Fq "${invariant}" <<<"${default_render}" || fail "Secure default render is missing: ${invariant}"
done

echo "Helm security render contract passed"
