#!/usr/bin/env bash
set -euo pipefail

chart_dir="${1:-deploy/helm/mock-fleet}"

disabled_render=$(helm template unusual-release "${chart_dir}" --namespace testing)
if grep -Fq 'app.kubernetes.io/component: mock-ops' <<<"${disabled_render}"; then
  echo "Mock Ops resources must be disabled by default" >&2
  exit 1
fi

if helm template unusual-release "${chart_dir}" \
    --namespace testing \
    --set wiremock.versionUpdater.enabled=true \
    >/dev/null 2>&1; then
  echo "Chart accepted removed wiremock.versionUpdater values" >&2
  exit 1
fi

default_enabled_render=$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set mockOps.enabled=true \
  --show-only templates/mock-ops-cronjob.yaml)
for default_fragment in \
  'schedule: "0 2 * * *"' \
  'timeZone: "Etc/UTC"' \
  'app.kubernetes.io/component: mock-ops' \
  'serviceAccountName: custom-fleet-mock-ops' \
  'value: "3.x"' \
  'value: "5"'; do
  grep -Fq "${default_fragment}" <<<"${default_enabled_render}" \
    || { echo "Default Mock Ops render is missing: ${default_fragment}" >&2; exit 1; }
done
if grep -Fq 'MOCK_FLEET_WIREMOCK_REGISTRY_USERNAME' <<<"${default_enabled_render}"; then
  echo "Default Mock Ops render must not reference registry credentials" >&2
  exit 1
fi

enabled_render=$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set mockOps.enabled=true \
  --set mockOps.schedule='15 */6 * * *' \
  --set mockOps.timeZone=Europe/Belgrade \
  --set mockOps.defaultVersionConstraint=3.12.x \
  --set mockOps.minorLines=2 \
  --set mockOps.registry.url=http://registry.testing.svc:5000 \
  --set mockOps.registry.repository=mirror/wiremock \
  --set mockOps.registry.imageRepository=registry.testing.svc:5000/mirror/wiremock \
  --set mockOps.registry.credentialsSecretName=registry-credentials \
  --set mockOps.image.repository=example.test/mock-fleet/mock-ops \
  --set mockOps.image.tag=test-tag \
  --set mockOps.image.pullPolicy=Never \
  --show-only templates/mock-ops-cronjob.yaml \
  --show-only templates/mock-ops-serviceaccount.yaml \
  --show-only templates/mock-ops-role.yaml \
  --show-only templates/mock-ops-rolebinding.yaml)

for fragment in \
  'kind: CronJob' \
  'schedule: "15 */6 * * *"' \
  'timeZone: "Europe/Belgrade"' \
  'concurrencyPolicy: Forbid' \
  'backoffLimit: 0' \
  'app.kubernetes.io/component: mock-ops' \
  'image: example.test/mock-fleet/mock-ops:test-tag' \
  'imagePullPolicy: Never' \
  'serviceAccountName: custom-fleet-mock-ops' \
  'automountServiceAccountToken: true' \
  'readOnlyRootFilesystem: true' \
  'allowPrivilegeEscalation: false' \
  'runAsNonRoot: true' \
  'runAsUser: 1001' \
  'type: RuntimeDefault' \
  'name: MOCK_FLEET_WIREMOCK_DEFAULT_VERSION_CONSTRAINT' \
  'value: "3.12.x"' \
  'name: MOCK_FLEET_WIREMOCK_MINOR_LINES' \
  'value: "2"' \
  'value: "http://registry.testing.svc:5000"' \
  'value: "mirror/wiremock"' \
  'name: MOCK_FLEET_WIREMOCK_IMAGE_REPOSITORY' \
  'value: "registry.testing.svc:5000/mirror/wiremock"' \
  'key: username' \
  'key: password'; do
  grep -Fq "${fragment}" <<<"${enabled_render}" \
    || { echo "Enabled Mock Ops render is missing: ${fragment}" >&2; exit 1; }
done

expected_baseline_rule=$'  - apiGroups:\n      - ""\n    resources:\n      - configmaps\n    resourceNames:\n      - custom-fleet-wiremock-config\n    verbs:\n      - get'
expected_user_rule=$'      - custom-fleet-wiremock-user-config\n    verbs:\n      - get'
expected_catalog_rule=$'      - custom-fleet-wiremock-version-catalog\n    verbs:\n      - get\n      - update\n      - patch'
for rule in "${expected_baseline_rule}" "${expected_user_rule}" "${expected_catalog_rule}"; do
  grep -Fq "${rule}" <<<"${enabled_render}" \
    || { echo "Mock Ops RBAC rule is missing or broader than required" >&2; exit 1; }
done
for forbidden in '- list' '- watch' '- create' '- delete'; do
  if grep -Fq -- "${forbidden}" <<<"${enabled_render}"; then
    echo "Mock Ops RBAC must not grant ${forbidden}" >&2
    exit 1
  fi
done

for invalid_constraint in 3 3.12 3.12.1 4.x 3.-1.x; do
  if helm template unusual-release "${chart_dir}" \
      --set mockOps.enabled=true \
      --set-string mockOps.defaultVersionConstraint="${invalid_constraint}" \
      >/dev/null 2>&1; then
    echo "Chart accepted invalid Mock Ops constraint: ${invalid_constraint}" >&2
    exit 1
  fi
done

for valid_minor_lines in 1 50; do
  helm template unusual-release "${chart_dir}" \
    --set mockOps.enabled=true \
    --set mockOps.minorLines="${valid_minor_lines}" >/dev/null
done

for invalid_minor_lines in 0 51; do
  if helm template unusual-release "${chart_dir}" \
      --set mockOps.enabled=true \
      --set mockOps.minorLines="${invalid_minor_lines}" >/dev/null 2>&1; then
    echo "Chart accepted invalid Mock Ops minorLines: ${invalid_minor_lines}" >&2
    exit 1
  fi
done

for invalid_setting in \
  'mockOps.schedule=' \
  'mockOps.registry.url=' \
  'mockOps.registry.repository='; do
  if helm template unusual-release "${chart_dir}" \
      --set mockOps.enabled=true \
      --set-string "${invalid_setting}" >/dev/null 2>&1; then
    echo "Chart accepted invalid Mock Ops value: ${invalid_setting}" >&2
    exit 1
  fi
done

for valid_image_repository in \
  team/mock--image \
  registry.testing:5000/team/mock__image \
  registry--prod.example:5000/team/image; do
  helm template unusual-release "${chart_dir}" \
    --set mockOps.enabled=true \
    --set-string mockOps.registry.imageRepository="${valid_image_repository}" \
    >/dev/null
done

for invalid_image_repository in \
  https://registry.testing/wiremock \
  wiremock:3 \
  team/-mock \
  team/mock_ \
  team/mock:::image \
  team//mock \
  -registry.example/team/image \
  registry-.example/team/image \
  registry.example:port/team/image \
  registry.example:/team/image \
  '[::1]:5000/team/image'; do
  if helm template unusual-release "${chart_dir}" \
      --set mockOps.enabled=true \
      --set-string mockOps.registry.imageRepository="${invalid_image_repository}" \
      >/dev/null 2>&1; then
    echo "Chart accepted invalid image repository: ${invalid_image_repository}" >&2
    exit 1
  fi
done

echo "Mock Ops render contract passed."
