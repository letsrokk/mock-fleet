#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "${script_dir}/.." && pwd)
chart_dir="${repo_root}/deploy/helm/mock-fleet"
fixture_dir="${repo_root}/tests/cluster/fixtures"
security_render_script="${repo_root}/deploy/helm/mock-fleet/tests/render-security.sh"

mode=live
keep=false
retain_success=${MOCK_FLEET_E2E_RETAIN:-false}
run_id=${MOCK_FLEET_E2E_RUN_ID:-$(date -u +%Y%m%d%H%M%S)-$$}
run_id=$(printf '%s' "${run_id}" | tr '[:upper:]_' '[:lower:]-' | tr -cd 'a-z0-9-')
run_id=${run_id:0:24}
ownership_token=$(od -An -N16 -tx1 /dev/urandom | tr -d '[:space:]')
if [[ ! "${ownership_token}" =~ ^[0-9a-f]{32}$ ]]; then
  printf '[cluster-e2e] ERROR: Unable to generate an S3 ownership token.\n' >&2
  exit 1
fi
ownership_suffix=${ownership_token:0:12}
namespace="mock-fleet-e2e-${run_id}"
release="fleet-${run_id}"
bucket="mock-fleet-e2e-data-${run_id}-${ownership_suffix}"
bucket_owner_key=".mock-fleet-e2e-owner"
mcp_port=${MOCK_FLEET_E2E_MCP_PORT:-18080}
api_port=${MOCK_FLEET_E2E_API_PORT:-18081}
timeout_seconds=${MOCK_FLEET_E2E_TIMEOUT_SECONDS:-180}
storage_class=${MOCK_FLEET_E2E_STORAGE_CLASS:-seaweedfs-s3}
s3_provisioner=${MOCK_FLEET_E2E_S3_PROVISIONER:-s3.csi.aws.com}
s3_endpoint=${MOCK_FLEET_E2E_S3_ENDPOINT:-http://seaweedfs-s3.seaweedfs.svc.cluster.local:8333}
s3_access_key=${MOCK_FLEET_E2E_S3_ACCESS_KEY:-}
s3_secret_key=${MOCK_FLEET_E2E_S3_SECRET_KEY:-}
s3_admin_image=${MOCK_FLEET_E2E_S3_ADMIN_IMAGE:-amazon/aws-cli:2.17.57}
wiremock_image=${MOCK_FLEET_E2E_WIREMOCK_IMAGE:-wiremock/wiremock:3.13.2-2}
wiremock_secondary_image=${MOCK_FLEET_E2E_WIREMOCK_SECONDARY_IMAGE:-wiremock/wiremock:3.12.1-2}
wiremock_repository=${wiremock_image%:*}
wiremock_tag=${wiremock_image##*:}
wiremock_version=${wiremock_tag%%-*}
wiremock_secondary_repository=${wiremock_secondary_image%:*}
wiremock_secondary_tag=${wiremock_secondary_image##*:}
wiremock_secondary_version=${wiremock_secondary_tag%%-*}
image_tag=${MOCK_FLEET_E2E_IMAGE_TAG:-latest}
routing_mode=${MOCK_FLEET_E2E_ROUTING_MODE:-PATH}
routing_mode=$(printf '%s' "${routing_mode}" | tr '[:lower:]' '[:upper:]')
proxy_image=${MOCK_FLEET_E2E_PROXY_IMAGE:-ghcr.io/letsrokk/mock-fleet/proxy}
api_image=${MOCK_FLEET_E2E_API_IMAGE:-ghcr.io/letsrokk/mock-fleet/api}
mcp_image=${MOCK_FLEET_E2E_MCP_IMAGE:-ghcr.io/letsrokk/mock-fleet/mcp}
dash_image=${MOCK_FLEET_E2E_DASH_IMAGE:-ghcr.io/letsrokk/mock-fleet/dash}
updater_image=${MOCK_FLEET_E2E_UPDATER_IMAGE:-ghcr.io/letsrokk/mock-fleet/wiremock-updater}
work_dir=""
mcp_pf_pid=""
api_pf_pid=""
mcp_session=""
namespace_created=false
bucket_created=false
admission_policy_name=""

usage() {
  cat <<EOF
Usage: $(basename "$0") [--self-test|--dry-run] [--keep|--retain]

Run the opt-in Mock Fleet Minikube/SeaweedFS contract suite.

Modes:
  --self-test  Check script syntax, fixtures, identifier isolation, and dry-run output.
  --dry-run    Print resolved resources and prerequisites without changing the cluster.
  --keep       Keep the failed live-run namespace and bucket for investigation.
  --retain     Keep a successful populated namespace and bucket for inspection.

Live-run prerequisites:
  minikube, kubectl, helm, curl, jq, awk, sed, and a running Minikube profile
  CSI driver MOCK_FLEET_E2E_S3_PROVISIONER (default: ${s3_provisioner})
  StorageClass MOCK_FLEET_E2E_STORAGE_CLASS (default: ${storage_class})
  reachable SeaweedFS S3 endpoint MOCK_FLEET_E2E_S3_ENDPOINT
  MOCK_FLEET_E2E_S3_ACCESS_KEY and MOCK_FLEET_E2E_S3_SECRET_KEY

The suite creates and owns namespace ${namespace} and bucket ${bucket}. It proves
that both are absent before creation, verifies a new bucket is empty, records a hidden
per-execution token in an S3 ownership marker, and refuses to reuse or delete an unverified bucket. Image repository/tag and local
ports are configurable through the MOCK_FLEET_E2E_* variables documented in this script.
EOF
}

log() {
  printf '[cluster-e2e] %s\n' "$*"
}

fail() {
  printf '[cluster-e2e] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing prerequisite '$1'. Install it and retry."
}

current_kubernetes_minor_version() {
  local version_json major minor
  version_json=$(kubectl version -o json)
  major=$(jq -r '.serverVersion.major // empty' <<<"${version_json}")
  minor=$(jq -r '.serverVersion.minor // empty' <<<"${version_json}")
  minor=${minor%%[!0-9]*}
  [[ -n "${major}" && -n "${minor}" ]] || fail "Unable to determine the current Kubernetes minor version."
  printf 'v%s.%s\n' "${major}" "${minor}"
}

label_namespace_for_restricted_psa() {
  local target_namespace=$1
  local version
  version=$(current_kubernetes_minor_version)
  kubectl label namespace "${target_namespace}" --overwrite \
    pod-security.kubernetes.io/enforce=restricted \
    "pod-security.kubernetes.io/enforce-version=${version}" \
    pod-security.kubernetes.io/audit=restricted \
    "pod-security.kubernetes.io/audit-version=${version}" \
    pod-security.kubernetes.io/warn=restricted \
    "pod-security.kubernetes.io/warn-version=${version}"
}

admission_fixture() {
  local variant=$1
  "${security_render_script}" --admission-fixture "${variant}" "${namespace}" mock-fleet \
    "${release}-wiremock" "${wiremock_image}" true "${release}-pvc" "/mock-fleet/${run_id}"
}

server_dry_run_admission_fixture() {
  local variant=$1
  local expectation=$2
  local output
  if output=$(admission_fixture "${variant}" | kubectl create --dry-run=server \
      --as="system:serviceaccount:${namespace}:${release}-pod-manager" -f - 2>&1); then
    [[ "${expectation}" == accepted ]] \
      || fail "Admission accepted rejected fixture ${variant}."
  else
    [[ "${expectation}" == rejected ]] || {
      printf '%s\n' "${output}" >&2
      fail "Admission rejected accepted fixture ${variant}."
    }
    [[ -n "${output}" ]] || fail "Fixture ${variant} was rejected without an API diagnostic."
  fi
}

verify_admission_dry_runs() {
  local variant
  if [[ -z "${admission_policy_name}" ]]; then
    admission_policy_name=$(kubectl get validatingadmissionpolicy \
      -l "app.kubernetes.io/instance=${release}" -o json | jq -er '
        [.items[].metadata.name] | if length == 1 then .[0] else empty end') \
      || fail "Unable to resolve the release-scoped WireMock admission policy."
  fi
  server_dry_run_admission_fixture accepted accepted
  server_dry_run_admission_fixture accepted-workload-identity accepted
  server_dry_run_admission_fixture accepted-eks-pod-identity accepted
  for variant in privileged hostpath wrong-image wrong-service-account label-spoofing \
      missing-limits excessive-limits alternate-sidecar identity-alternate-mount identity-init-subpath \
      native-init-sidecar pod-apparmor-unconfined container-apparmor-unconfined \
      init-apparmor-unconfined deprecated-apparmor-annotation pod-selinux-user \
      container-selinux-type init-selinux-role container-procmount-unmasked init-procmount-unmasked \
      missing-init-resources wrong-run-as-user \
      eks-label-without-token eks-token-without-label eks-label-wrong-value extra-unrelated-label \
      eks-nonstandard-token-path eks-alternate-volume-name eks-dual-irsa-mount eks-second-mount \
      eks-duplicate-full-uri eks-duplicate-token-file irsa-duplicate-role-env \
      mixed-eks-irsa-identity eks-env-without-label irsa-env-without-volume \
      second-irsa-identity unconfigured-custom-audience-irsa; do
    server_dry_run_admission_fixture "${variant}" rejected
  done
}

verify_api_rbac() {
  local api_username="system:serviceaccount:${namespace}:${release}-pod-manager"
  [[ $(kubectl auth can-i create pods --namespace "${namespace}" --as="${api_username}") == yes ]] \
    || fail "The API service account cannot create managed pods."
  [[ $(kubectl auth can-i create deployments.apps --namespace "${namespace}" --as="${api_username}") == no ]] \
    || fail "The API service account can create deployments."
  [[ $(kubectl auth can-i patch deployments.apps --namespace "${namespace}" --as="${api_username}") == no ]] \
    || fail "The API service account can patch deployments."
  [[ $(kubectl auth can-i create configmaps --namespace "${namespace}" --as="${api_username}") == no ]] \
    || fail "The API service account can create arbitrary ConfigMaps."
  [[ $(kubectl auth can-i patch "configmap/${release}-wiremock-user-config" \
      --namespace "${namespace}" --as="${api_username}") == yes ]] \
    || fail "The API service account cannot patch the release-owned user ConfigMap."
  [[ $(kubectl auth can-i patch configmap/unrelated \
      --namespace "${namespace}" --as="${api_username}") == no ]] \
    || fail "The API service account can patch an unrelated ConfigMap."
}

verify_updater_rbac() {
  local updater_username="system:serviceaccount:${namespace}:${release}-wiremock-updater"
  for config_map in "${release}-wiremock-config" "${release}-wiremock-user-config"; do
    [[ $(kubectl auth can-i get "configmap/${config_map}" --namespace "${namespace}" --as="${updater_username}") == yes ]] \
      || fail "The updater service account cannot read ${config_map}."
    [[ $(kubectl auth can-i update "configmap/${config_map}" --namespace "${namespace}" --as="${updater_username}") == no ]] \
      || fail "The updater service account can update read-only ConfigMap ${config_map}."
  done
  for verb in get update patch; do
    [[ $(kubectl auth can-i "${verb}" "configmap/${release}-wiremock-version-catalog" \
        --namespace "${namespace}" --as="${updater_username}") == yes ]] \
      || fail "The updater service account cannot ${verb} the version catalog."
  done
  [[ $(kubectl auth can-i list configmaps --namespace "${namespace}" --as="${updater_username}") == no ]] \
    || fail "The updater service account can list ConfigMaps."
  [[ $(kubectl auth can-i get pods --namespace "${namespace}" --as="${updater_username}") == no ]] \
    || fail "The updater service account can read Pods."
}

pods_have_no_general_api_token() {
  jq -e '
    all(.items[];
      .spec.automountServiceAccountToken == false and
      all(.spec.volumes[]?;
        (.projected.sources // [])
        | all(.[]; (.serviceAccountToken // null) == null or
            (.serviceAccountToken.audience == "sts.amazonaws.com" or
             .serviceAccountToken.audience == "pods.eks.amazonaws.com"))))
  ' >/dev/null
}

verify_fixed_workloads_are_tokenless() {
  local pods
  pods=$(kubectl get pods --namespace "${namespace}" \
    -l 'app.kubernetes.io/component in (proxy,dash,mcp)' -o json)
  jq -e '
    (["proxy", "dash", "mcp"] -
      ([.items[].metadata.labels["app.kubernetes.io/component"]] | unique) | length) == 0
  ' >/dev/null <<<"${pods}" \
    || fail "Expected Proxy, Dash, and MCP Pods are not all present for token verification."
  pods_have_no_general_api_token <<<"${pods}" \
    || fail "Proxy, Dash, or MCP received a general Kubernetes API token volume."
}

verify_wiremock_workload_identity() {
  local mock_id=$1
  local pods
  pods=$(kubectl get pods --namespace "${namespace}" \
    -l "mock-fleet/mock-id=${mock_id}" -o json)
  jq -e --arg service_account "${release}-wiremock" '
    (.items | length) == 1 and
    .items[0].spec.serviceAccountName == $service_account and
    .items[0].spec.automountServiceAccountToken == false
  ' >/dev/null <<<"${pods}" || fail "Managed WireMock did not retain its dedicated tokenless service account."
  pods_have_no_general_api_token <<<"${pods}" \
    || fail "Managed WireMock received a general Kubernetes API token volume."
}

assert_jq() {
  local json=$1
  local expression=$2
  local message=$3
  if ! jq -e "${expression}" >/dev/null <<<"${json}"; then
    printf '%s\n' "${json}" | jq . >&2 || true
    fail "${message} (jq: ${expression})"
  fi
}

assert_option_catalog_contract() {
  local result=$1
  local expected_version=$2
  if ! jq -e --arg version "${expected_version}" '
    (. | keys | sort) == ["catalogStatus", "options", "wireMockVersion"] and
    .wireMockVersion == $version and
    (.catalogStatus == "supported" or .catalogStatus == "newer_unresearched") and
    (.options | type == "array" and length > 0) and
    all(.options[];
      (. | keys | sort) == ["description", "group", "kind", "label", "maximum", "minimum", "name", "values"] and
      (.name | type == "string") and
      (.values | type == "array")) and
    ([.options[].name] | index("--verbose") != null)
  ' >/dev/null <<<"${result}"; then
    printf '%s\n' "${result}" | jq . >&2 || true
    fail "MCP option definitions do not match the versioned catalog response contract."
  fi
}

poll_until() {
  local description=$1
  local deadline=$((SECONDS + timeout_seconds))
  shift
  while (( SECONDS < deadline )); do
    if "$@"; then
      return 0
    fi
    sleep 1
  done
  fail "Timed out after ${timeout_seconds}s waiting for ${description}."
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  [[ -n "${mcp_pf_pid}" ]] && kill "${mcp_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${api_pf_pid}" ]] && kill "${api_pf_pid}" >/dev/null 2>&1 || true
  if [[ ( "${keep}" == true && ${exit_code} -ne 0 ) || ( "${retain_success}" == true && ${exit_code} -eq 0 ) ]]; then
    log "Keeping namespace ${namespace} and bucket ${bucket}."
  else
    kubectl delete pod --namespace "${namespace}" \
      -l 'app.kubernetes.io/name=mock-fleet-wiremock,app.kubernetes.io/managed-by=mock-fleet' \
      --ignore-not-found --wait=true --timeout="${timeout_seconds}s" >/dev/null 2>&1 || true
    helm uninstall "${release}" --namespace "${namespace}" --ignore-not-found >/dev/null 2>&1 || true
    if [[ "${bucket_created}" == true && "${namespace_created}" == true ]]; then
      if bucket_ownership_marker_matches; then
        run_s3_cli cleanup s3 rm "s3://${bucket}" --recursive >/dev/null 2>&1 || true
        run_s3_cli delete s3api delete-bucket --bucket "${bucket}" >/dev/null 2>&1 || true
      else
        log "WARNING: Refusing to empty or delete ${bucket}; its ownership marker does not match this execution."
      fi
    fi
    kubectl delete pv "${release}-pv" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    if [[ "${namespace_created}" == true ]]; then
      kubectl delete namespace "${namespace}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    fi
  fi
  [[ -n "${work_dir}" ]] && rm -rf "${work_dir}"
  exit "${exit_code}"
}

run_s3_cli() {
  local suffix=$1
  shift
  local pod="${release}-s3-${suffix}"
  local overrides
  overrides=$(jq -cn --arg pod "${pod}" '{
    spec:{
      securityContext:{runAsNonRoot:true,seccompProfile:{type:"RuntimeDefault"}},
      containers:[{
        name:$pod,
        securityContext:{
          runAsNonRoot:true,
          runAsUser:1000,
          allowPrivilegeEscalation:false,
          capabilities:{drop:["ALL"]},
          seccompProfile:{type:"RuntimeDefault"}
        },
        resources:{requests:{cpu:"25m",memory:"32Mi"},limits:{cpu:"250m",memory:"256Mi"}}
      }]
    }
  }')
  kubectl delete pod "${pod}" --namespace "${namespace}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl run "${pod}" --namespace "${namespace}" --restart=Never --image="${s3_admin_image}" \
    --overrides="${overrides}" --override-type=strategic \
    --env="AWS_ACCESS_KEY_ID=${s3_access_key}" \
    --env="AWS_SECRET_ACCESS_KEY=${s3_secret_key}" \
    --env="AWS_DEFAULT_REGION=us-east-1" \
    -- "$@" --endpoint-url "${s3_endpoint}" >/dev/null
  if ! kubectl wait pod/"${pod}" --namespace "${namespace}" \
      --for=jsonpath='{.status.phase}'=Succeeded --timeout="${timeout_seconds}s" >/dev/null 2>&1; then
    kubectl logs "${pod}" --namespace "${namespace}" >&2 || true
    return 1
  fi
  kubectl logs "${pod}" --namespace "${namespace}" || true
  kubectl delete pod "${pod}" --namespace "${namespace}" --wait=false >/dev/null 2>&1 || true
}

bucket_probe_state() {
  local probe_status=$1
  local listing=$2
  local listed_bucket
  if [[ ${probe_status} -ne 0 ]]; then
    printf 'ambiguous\n'
    return
  fi
  for listed_bucket in ${listing}; do
    if [[ "${listed_bucket}" == "${bucket}" ]]; then
      printf 'existing\n'
      return
    fi
  done
  printf 'absent\n'
}

bucket_name_for_token() {
  printf 'mock-fleet-e2e-data-%s-%s\n' "${run_id}" "${1:0:12}"
}

require_absent_bucket_state() {
  case "$1" in
    absent) return ;;
    existing)
      fail "Bucket ${bucket} already exists and is accessible. Choose a unique MOCK_FLEET_E2E_RUN_ID."
      ;;
    *)
      fail "Cannot prove that bucket ${bucket} is absent. Refusing creation after an ambiguous or forbidden S3 probe."
      ;;
  esac
}

bucket_ownership_marker_matches() {
  local marker_owner
  if ! marker_owner=$(run_s3_cli ownership s3api head-object --bucket "${bucket}" \
      --key "${bucket_owner_key}" --query Metadata.ownershiptoken --output text 2>/dev/null); then
    return 1
  fi
  ownership_marker_matches_token "${marker_owner}"
}

ownership_marker_matches_token() {
  [[ "$1" == "${ownership_token}" ]]
}

empty_bucket_probe_allows_ownership() {
  [[ $1 -eq 0 && "$2" == 0 ]]
}

write_bucket_ownership_marker() {
  run_s3_cli mark s3api put-object --bucket "${bucket}" --key "${bucket_owner_key}" \
    --body /etc/hosts --metadata "ownershiptoken=${ownership_token}"
}

create_recording_target() {
  kubectl create deployment recording-target --namespace "${namespace}" \
    --image=hashicorp/http-echo:0.2.3 --dry-run=client -o json \
    -- /http-echo --listen=:5678 --text=recorded \
    | jq '.spec.template.spec.securityContext = {runAsNonRoot:true,seccompProfile:{type:"RuntimeDefault"}}
      | .spec.template.spec.containers[0].securityContext = {
          runAsNonRoot:true,
          runAsUser:1000,
          allowPrivilegeEscalation:false,
          capabilities:{drop:["ALL"]},
          seccompProfile:{type:"RuntimeDefault"}
        }' \
    | kubectl apply -f -
}

create_fake_registry() {
  jq -cn --arg namespace "${namespace}" --arg repository "${wiremock_repository}" \
    --arg primary_tag "${wiremock_tag}" --arg secondary_tag "${wiremock_secondary_tag}" '
    {
      apiVersion:"v1",
      kind:"List",
      items:[
        {
          apiVersion:"apps/v1",
          kind:"Deployment",
          metadata:{name:"wiremock-registry",namespace:$namespace},
          spec:{
            replicas:1,
            selector:{matchLabels:{app:"wiremock-registry"}},
            template:{
              metadata:{labels:{app:"wiremock-registry"}},
              spec:{
                automountServiceAccountToken:false,
                securityContext:{runAsNonRoot:true,seccompProfile:{type:"RuntimeDefault"}},
                containers:[{
                  name:"registry",
                  image:"hashicorp/http-echo:0.2.3",
                  args:["-listen=:8080",("-text=" + ({name:$repository,tags:[$primary_tag,$secondary_tag]} | tojson))],
                  ports:[{name:"http",containerPort:8080}],
                  securityContext:{
                    runAsNonRoot:true,
                    runAsUser:1000,
                    allowPrivilegeEscalation:false,
                    readOnlyRootFilesystem:true,
                    capabilities:{drop:["ALL"]},
                    seccompProfile:{type:"RuntimeDefault"}
                  },
                  resources:{requests:{cpu:"10m",memory:"16Mi"},limits:{cpu:"100m",memory:"64Mi"}}
                }]
              }
            }
          }
        },
        {
          apiVersion:"v1",
          kind:"Service",
          metadata:{name:"wiremock-registry",namespace:$namespace},
          spec:{selector:{app:"wiremock-registry"},ports:[{name:"http",port:8080,targetPort:"http"}]}
        }
      ]
    }
  ' | kubectl apply -f -
  kubectl rollout status deployment/wiremock-registry --namespace "${namespace}" --timeout="${timeout_seconds}s" >/dev/null
}

catalog_has_reconciled_versions() {
  jq -e --arg default_version "${wiremock_version}" \
    --arg previous_version "${wiremock_secondary_version}" \
    --arg default_image "${wiremock_image}" --arg previous_image "${wiremock_secondary_image}" '
    .data.defaultVersion == $default_version and
    .data["selectable." + $default_version] == $default_image and
    .data["selectable." + $previous_version] == $previous_image and
    ([.data | keys[] | select(startswith("selectable."))] | length) == 2
  ' >/dev/null <<<"$1"
}

run_updater_reconciliation() {
  local catalog_name="${release}-wiremock-version-catalog"
  local job_name="${release}-wiremock-updater-e2e"
  local seed_patch catalog
  seed_patch=$(jq -cn --arg version "${wiremock_secondary_version}" \
    --arg image "${wiremock_secondary_image}" \
    '{data:{defaultVersion:$version,("selectable." + $version):$image}}
      | [{op:"replace",path:"/data",value:.data}]')
  kubectl patch configmap "${catalog_name}" --namespace "${namespace}" --type=json -p "${seed_patch}" >/dev/null
  kubectl delete job "${job_name}" --namespace "${namespace}" --ignore-not-found --wait=true >/dev/null
  kubectl create job "${job_name}" --namespace "${namespace}" \
    --from="cronjob/${release}-wiremock-updater" >/dev/null
  if ! kubectl wait "job/${job_name}" --namespace "${namespace}" \
      --for=condition=complete --timeout="${timeout_seconds}s" >/dev/null; then
    kubectl logs "job/${job_name}" --namespace "${namespace}" >&2 || true
    fail "WireMock updater reconciliation Job did not complete."
  fi
  catalog=$(kubectl get configmap "${catalog_name}" --namespace "${namespace}" -o json)
  catalog_has_reconciled_versions "${catalog}" \
    || fail "WireMock updater did not restore the two-version catalog from Registry V2."
}

helm_deploy() {
  local selected_wiremock_image=$1
  local selected_pull_policy=$2
  local target_host="recording-target.${namespace}.svc.cluster.local"
  local target_ip
  target_ip=$(kubectl get service recording-target --namespace "${namespace}" -o jsonpath='{.spec.clusterIP}')
  helm upgrade --install "${release}" "${chart_dir}" --namespace "${namespace}" \
    --set fullnameOverride="${release}" \
    --set fleet.api.replicas=2 \
    --set fleet.proxy.replicas=1 \
    --set fleet.proxy.routing.mode="${routing_mode}" \
    --set fleet.mcp.enabled=true \
    --set fleet.mcp.replicas=1 \
    --set "fleet.mcp.outbound.exceptions[0]=${target_host}" \
    --set "fleet.mcp.outbound.networkPolicy.allowedCidrs[0]=${target_ip}/32" \
    --set fleet.proxy.image.repository="${proxy_image}" \
    --set fleet.proxy.image.tag="${image_tag}" \
    --set fleet.proxy.image.pullPolicy=IfNotPresent \
    --set fleet.api.image.repository="${api_image}" \
    --set fleet.api.image.tag="${image_tag}" \
    --set fleet.api.image.pullPolicy=IfNotPresent \
    --set fleet.mcp.image.repository="${mcp_image}" \
    --set fleet.mcp.image.tag="${image_tag}" \
    --set fleet.mcp.image.pullPolicy=IfNotPresent \
    --set fleet.dash.image.repository="${dash_image}" \
    --set fleet.dash.image.tag="${image_tag}" \
    --set fleet.dash.image.pullPolicy=IfNotPresent \
    --set wiremock.versionUpdater.enabled=true \
    --set wiremock.versionUpdater.schedule='0 0 31 2 *' \
    --set wiremock.versionUpdater.timeZone=Etc/UTC \
    --set wiremock.versionUpdater.defaultVersionConstraint=3.x \
    --set wiremock.versionUpdater.minorLines=2 \
    --set wiremock.versionUpdater.registry.url="http://wiremock-registry.${namespace}.svc.cluster.local:8080" \
    --set wiremock.versionUpdater.registry.repository="${wiremock_repository}" \
    --set wiremock.versionUpdater.image.repository="${updater_image}" \
    --set wiremock.versionUpdater.image.tag="${image_tag}" \
    --set wiremock.versionUpdater.image.pullPolicy=IfNotPresent \
    --set wiremock.containerImage="${selected_wiremock_image}" \
    --set "wiremock.supportedImageTags[0]=${selected_wiremock_image##*:}" \
    --set "wiremock.supportedImageTags[1]=${wiremock_secondary_tag}" \
    --set wiremock.containerImagePullPolicy="${selected_pull_policy}" \
    --set storage.persistent=true \
    --set storage.type=s3 \
    --set storage.s3.provisioner="${s3_provisioner}" \
    --set storage.s3.storageClassName="${storage_class}" \
    --set storage.s3.bucket="${bucket}" \
    --set storage.s3.path="/mock-fleet/${run_id}" \
    --set "storage.s3.mountOptions[0]=endpoint-url ${s3_endpoint}" \
    --set 'storage.s3.mountOptions[1]=force-path-style' \
    --set 'storage.s3.mountOptions[2]=allow-delete' \
    --set 'storage.s3.mountOptions[3]=allow-overwrite' \
    --set 'storage.s3.mountOptions[4]=metadata-ttl minimal' \
    --set 'storage.s3.mountOptions[5]=region us-east-1' \
    --wait --timeout="${timeout_seconds}s"
}

extract_mcp_json() {
  local file=$1
  if jq -e . "${file}" >/dev/null 2>&1; then
    cat "${file}"
    return
  fi
  local data
  data=$(sed -n 's/^data: //p' "${file}" | tail -n 1)
  jq -e . >/dev/null <<<"${data}" || fail "MCP returned neither JSON nor JSON SSE data: $(cat "${file}")"
  printf '%s\n' "${data}"
}

extract_mcp_session_id() {
  awk 'tolower($1) == "mcp-session-id:" {gsub("\r", "", $2); print $2}' | tail -n 1
}

mcp_post() {
  local payload=$1
  local headers_file="${work_dir}/mcp-headers"
  local body_file="${work_dir}/mcp-body"
  local request_headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
  if [[ -n "${mcp_session}" ]]; then
    request_headers+=(-H "Mcp-Session-Id: ${mcp_session}" -H 'Mcp-Protocol-Version: 2025-11-25')
  fi
  curl --fail-with-body --silent --show-error -D "${headers_file}" -o "${body_file}" \
    "${request_headers[@]}" --data "${payload}" "http://127.0.0.1:${mcp_port}/__fleet/mcp"
  extract_mcp_json "${body_file}"
}

mcp_notify() {
  local payload=$1
  local status
  if ! status=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      -H "Mcp-Session-Id: ${mcp_session}" -H 'Mcp-Protocol-Version: 2025-11-25' \
      --data "${payload}" "http://127.0.0.1:${mcp_port}/__fleet/mcp"); then
    return 1
  fi
  if [[ "${status}" != 202 ]]; then
    printf '[cluster-e2e] ERROR: MCP notification returned HTTP %s instead of 202.\n' "${status}" >&2
    return 1
  fi
}

initialize_mcp() {
  local payload response
  payload='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"cluster-e2e","version":"1"}}}'
  response=$(mcp_post "${payload}")
  assert_jq "${response}" '.result.protocolVersion == "2025-11-25"' "MCP initialization failed"
  mcp_session=$(extract_mcp_session_id <"${work_dir}/mcp-headers")
  [[ -n "${mcp_session}" ]] || fail "MCP initialization did not return Mcp-Session-Id."
  mcp_notify '{"jsonrpc":"2.0","method":"notifications/initialized"}'
}

mcp_tool_raw() {
  local tool=$1
  local arguments=$2
  local payload
  payload=$(jq -cn --arg name "${tool}" --argjson arguments "${arguments}" \
    '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:$name,arguments:$arguments}}')
  mcp_post "${payload}" | jq -c '.result'
}

mcp_success() {
  local tool=$1
  local arguments=$2
  local result
  result=$(mcp_tool_raw "${tool}" "${arguments}")
  if [[ $(jq -r '.isError // false' <<<"${result}") == true ]]; then
    printf '%s\n' "${result}" | jq . >&2
    fail "MCP tool ${tool} returned an error."
  fi
  jq -c '.structuredContent' <<<"${result}"
}

mcp_expect_error() {
  local tool=$1
  local arguments=$2
  local code=$3
  local result
  result=$(mcp_tool_raw "${tool}" "${arguments}")
  assert_jq "${result}" ".isError == true and .structuredContent.error.code == \"${code}\"" \
    "${tool} did not return structured ${code}"
  jq -c '.structuredContent.error' <<<"${result}"
}

assert_mock_starting_contract() {
  local result=$1
  local mock_id=$2
  assert_jq "${result}" ".isError == true
    and .structuredContent.error.code == \"MOCK_STARTING\"
    and .structuredContent.error.retryable == true
    and .structuredContent.error.stateMayHaveChanged == false
    and .structuredContent.error.details.mockId == \"${mock_id}\"
    and .structuredContent.error.details.status == \"STARTING\"
    and .structuredContent.error.details.retryAfterMs == 1000" \
    "WireMock tool did not return the complete MOCK_STARTING contract"
}

mock_starting_retry_is_safe() {
  local result=$1
  local mock_id=$2
  jq -e --arg mock_id "${mock_id}" '
    .isError == true
    and .structuredContent.error.code == "MOCK_STARTING"
    and .structuredContent.error.retryable == true
    and .structuredContent.error.stateMayHaveChanged == false
    and .structuredContent.error.details.mockId == $mock_id
    and .structuredContent.error.details.status == "STARTING"
    and .structuredContent.error.details.retryAfterMs == 1000
  ' >/dev/null <<<"${result}"
}

assert_persistent_restart_state() {
  local result=$1
  local surviving_stub_id=$2
  local deleted_stub_id=$3
  assert_jq "${result}" ".stubs
    | (any((.id // .uuid) == \"${surviving_stub_id}\"))
      and (all((.id // .uuid) != \"${deleted_stub_id}\"))" \
    "Persistent restart did not preserve the updated stub and deletion"
}

poll_mcp_success() {
  local tool=$1
  local arguments=$2
  local result_file="${work_dir}/poll-result"
  poll_until "${tool} success" poll_mcp_attempt "${tool}" "${arguments}" "${result_file}"
  cat "${result_file}"
}

create_stub_when_ready() {
  poll_mcp_success create_stub "$1"
}

poll_mcp_attempt() {
  local tool=$1
  local arguments=$2
  local result_file=$3
  local result code mock_id
  result=$(mcp_tool_raw "${tool}" "${arguments}") || return 1
  if [[ $(jq -r '.isError // false' <<<"${result}") == false ]]; then
    jq -c '.structuredContent' <<<"${result}" >"${result_file}"
    return 0
  fi
  code=$(jq -r '.structuredContent.error.code' <<<"${result}")
  [[ "${code}" == MOCK_STARTING ]] || {
    printf '%s\n' "${result}" | jq . >&2
    fail "${tool} failed while polling with ${code}."
  }
  mock_id=$(jq -r '.mockId // empty' <<<"${arguments}")
  if [[ -z "${mock_id}" ]] || ! mock_starting_retry_is_safe "${result}" "${mock_id}"; then
    printf '%s\n' "${result}" | jq . >&2
    fail "${tool} returned an unsafe MOCK_STARTING retry contract."
  fi
  return 1
}

api_request() {
  local method=$1
  local path=$2
  local body=${3:-}
  local output="${work_dir}/api-body"
  local args=(--silent --show-error -o "${output}" -w '%{http_code}' -X "${method}" -H 'Accept: application/json')
  if [[ -n "${body}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${body}")
  fi
  api_status=$(curl "${args[@]}" "http://127.0.0.1:${api_port}${path}")
  api_body=$(cat "${output}")
}

start_api_port_forward() {
  if [[ -n "${api_pf_pid}" ]]; then
    kill "${api_pf_pid}" >/dev/null 2>&1 || true
    wait "${api_pf_pid}" >/dev/null 2>&1 || true
  fi
  kubectl port-forward --namespace "${namespace}" service/"${release}-api" "${api_port}:80" \
    >"${work_dir}/api-port-forward.log" 2>&1 &
  api_pf_pid=$!
  poll_until "API port-forward" curl --silent --fail --output /dev/null \
    "http://127.0.0.1:${api_port}/__fleet/api/config"
}

api_replacement_ready() {
  local old_pod=$1
  local pods
  pods=$(kubectl get pods --namespace "${namespace}" \
    -l 'app.kubernetes.io/component=api' -o json) || return 1
  jq -e --arg old_pod "${old_pod}" '
    [.items[]
      | select(.metadata.deletionTimestamp == null)
      | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
      | .metadata.name] as $ready
    | ($ready | length) == 2 and ($ready | index($old_pod)) == null
  ' >/dev/null <<<"${pods}"
}

restart_one_api_replica() {
  local old_pod
  old_pod=$(kubectl get pods --namespace "${namespace}" \
    -l 'app.kubernetes.io/component=api' -o jsonpath='{.items[0].metadata.name}')
  kubectl delete pod "${old_pod}" --namespace "${namespace}" --wait=false >/dev/null
  poll_until "replacement of API pod ${old_pod}" api_replacement_ready "${old_pod}"
  start_api_port_forward
}

api_mock_failed() {
  local mock_id=$1
  api_request GET /__fleet/api/mocks
  [[ "${api_status}" == 200 ]] || return 1
  jq -e --arg id "${mock_id}" '.[] | select(.mockId == $id and .status == "FAILED")' >/dev/null <<<"${api_body}"
}

api_mock_active_at_version() {
  local mock_id=$1
  local version=$2
  api_request GET /__fleet/api/config
  [[ "${api_status}" == 200 ]] || return 1
  jq -e --arg id "${mock_id}" --arg version "${version}" '
    .mocks[]
    | select(.mockId == $id and .lifecycle == "RUNNING" and
        .wireMockVersion == $version and .runtimeVersion == $version)
  ' >/dev/null <<<"${api_body}"
}

verify_two_wiremock_versions() {
  local current_mock="current-${run_id:0:12}"
  local previous_mock="previous-${run_id:0:11}"
  local rv mutation pod image
  local versions=("${wiremock_version}" "${wiremock_secondary_version}")
  local mocks=("${current_mock}" "${previous_mock}")
  local images=("${wiremock_image}" "${wiremock_secondary_image}")

  for index in 0 1; do
    api_request GET /__fleet/api/config
    [[ "${api_status}" == 200 ]] || fail "Unable to read config before selecting WireMock ${versions[index]}."
    rv=$(jq -r '.resourceVersion' <<<"${api_body}")
    mutation=$(jq -cn --arg rv "${rv}" --arg version "${versions[index]}" \
      '{resourceVersion:$rv,wireMockVersion:$version,options:[],resources:null,applyMode:"futureOnly"}')
    api_request PUT "/__fleet/api/config/${mocks[index]}" "${mutation}"
    [[ "${api_status}" == 200 ]] \
      || fail "Unable to select WireMock ${versions[index]}: ${api_body}"
    api_request POST "/__fleet/api/mocks/${mocks[index]}/start"
    [[ "${api_status}" == 200 || "${api_status}" == 202 ]] \
      || fail "Unable to start WireMock ${versions[index]}: ${api_body}"
    poll_until "${mocks[index]} on WireMock ${versions[index]}" \
      api_mock_active_at_version "${mocks[index]}" "${versions[index]}"
    pod=$(ready_wiremock_pod_name "${mocks[index]}") \
      || fail "No ready pod found for ${mocks[index]}."
    image=$(kubectl get pod "${pod}" --namespace "${namespace}" -o json | jq -er '
      .spec.containers[] | select(.name == "wiremock") | .image')
    [[ "${image}" == "${images[index]}" ]] \
      || fail "${mocks[index]} runs ${image}, expected ${images[index]}."
  done
}

ready_wiremock_pod_name_from_json() {
  local mock_id=$1
  local pods=$2
  jq -er --arg id "${mock_id}" '
    [.items[]
      | select(.metadata.deletionTimestamp == null)
      | select(.metadata.labels["mock-fleet/mock-id"] == $id)
      | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
      | .metadata.name]
    | if length == 1 then .[0] else empty end
  ' <<<"${pods}"
}

ready_wiremock_pod_name() {
  local mock_id=$1
  local pods
  pods=$(kubectl get pods --namespace "${namespace}" -o json) || return 1
  ready_wiremock_pod_name_from_json "${mock_id}" "${pods}"
}

wiremock_pod_was_replaced() {
  local mock_id=$1
  local old_pod=$2
  local new_pod
  new_pod=$(ready_wiremock_pod_name "${mock_id}") || return 1
  [[ "${new_pod}" != "${old_pod}" ]]
}

verify_api_replicas() {
  local mock_id=$1
  local expected_option=$2
  local pod config
  api_pods=()
  while IFS= read -r pod; do
    [[ -n "${pod}" ]] && api_pods+=("${pod}")
  done < <(kubectl get pods --namespace "${namespace}" \
    -l 'app.kubernetes.io/component=api' -o json | jq -r \
      '.items[] | select(.metadata.deletionTimestamp == null) | select(any(.status.conditions[]?; .type == "Ready" and .status == "True")) | .metadata.name')
  [[ ${#api_pods[@]} -eq 2 ]] || fail "Expected two API replicas, found ${#api_pods[@]}."
  for pod in "${api_pods[@]}"; do
    config=$(kubectl get --raw "/api/v1/namespaces/${namespace}/pods/${pod}:8080/proxy/__fleet/api/config")
    assert_jq "${config}" ".mocks[] | select(.mockId == \"${mock_id}\") | .user.options | index(\"${expected_option}\") != null" \
      "API pod ${pod} did not load the saved config"
  done
}

api_replicas_have_cpu_request() {
  local mock_id=$1
  local expected_cpu=$2
  local pod config
  local ready_pods=()
  while IFS= read -r pod; do
    [[ -n "${pod}" ]] && ready_pods+=("${pod}")
  done < <(kubectl get pods --namespace "${namespace}" \
    -l 'app.kubernetes.io/component=api' -o json | jq -r \
      '.items[] | select(.metadata.deletionTimestamp == null) | select(any(.status.conditions[]?; .type == "Ready" and .status == "True")) | .metadata.name')
  [[ ${#ready_pods[@]} -eq 2 ]] || return 1
  for pod in "${ready_pods[@]}"; do
    config=$(kubectl get --raw "/api/v1/namespaces/${namespace}/pods/${pod}:8080/proxy/__fleet/api/config") || return 1
    jq -e --arg id "${mock_id}" --arg cpu "${expected_cpu}" \
      '.mocks[] | select(.mockId == $id) | .effective.resources.requests.cpu == $cpu' \
      >/dev/null <<<"${config}" || return 1
  done
}

run_contracts() {
  local config rv mutation current_rv invalid_rv lifecycle result stub_id deleted_stub_id unmatched_stub_id candidate_id body_args body_file_pod
  local main_mock="m-${run_id:0:18}"
  local config_mock="cfg-${run_id:0:16}"
  local cold_mock="cold-${run_id:0:16}"
  local cleanup_mock="stop-${run_id:0:16}"
  local failed_mock="fail-${run_id:0:16}"

  log "Checking 31-tool discovery and renamed recorder status."
  result=$(mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/list"}')
  local expected_tools actual_tools sorted_expected_tools
  expected_tools='["list_mocks","get_mock_config","list_option_definitions","update_mock_config","delete_mock_config","start_mock","stop_mock","list_stubs","list_unmatched_stubs","get_stub","create_stub","update_stub","delete_stub","persist_stub","unpersist_stub","send_request","find_requests","count_requests","list_unmatched_requests","get_near_misses","reset_request_journal","start_recording","get_recording_status","stop_recording","snapshot_requests","list_body_files","get_body_file","put_body_file","delete_body_file","list_scenarios","reset_scenarios"]'
  actual_tools=$(jq -c '[.result.tools[].name] | sort' <<<"${result}")
  sorted_expected_tools=$(jq -c 'sort' <<<"${expected_tools}")
  [[ "${actual_tools}" == "${sorted_expected_tools}" ]] \
    || fail "MCP registered-tool coverage manifest drifted"
  assert_jq "${result}" '[.result.tools[].name] | index("start_mock") != null and index("get_recording_status") != null and index("recording_status") == null' \
    "MCP lifecycle/recording tool names are incorrect"

  result=$(curl --silent --fail "http://127.0.0.1:${mcp_port}/__fleet/mcp/version")
  assert_jq "${result}" '.component == "mcp" and (.revision | test("^[0-9a-fA-F]{40}$")) and (.buildTime | fromdateiso8601 > 0)' \
    "MCP version endpoint omitted build provenance"
  result=$(curl --silent --fail "http://127.0.0.1:${mcp_port}/__fleet/mcp/health/ready")
  assert_jq "${result}" '.status == "UP" and ([.checks[].name] | index("fleet-api") != null and index("fleet-proxy") != null)' \
    "MCP readiness did not include healthy Fleet dependencies"

  mcp_expect_error get_mock_config '{}' INVALID_ARGUMENT >/dev/null
  mcp_expect_error get_mock_config '{"mockId":"orders","unexpected":true}' INVALID_ARGUMENT >/dev/null
  mcp_expect_error put_body_file '{"mockId":"orders","fileName":"bad","body":{"encoding":"utf8","data":"x","sizeBytes":1},"contentType":"text/plain"}' INVALID_ARGUMENT >/dev/null

  result=$(mcp_success list_mocks '{"limit":1}')
  assert_jq "${result}" '.page | has("limit") and has("returned") and has("hasMore") and has("nextCursor")' \
    "MCP collection metadata is incomplete"
  result=$(mcp_success list_option_definitions '{}')
  assert_option_catalog_contract "${result}" "${wiremock_version}"

  api_request GET /__fleet/api/config
  rv=$(jq -r '.resourceVersion' <<<"${api_body}")
  result=$(mcp_success update_mock_config "$(jq -cn --arg id "${config_mock}" --arg rv "${rv}" \
    '{mockId:$id,resourceVersion:$rv,options:[],applyMode:"futureOnly"}')")
  rv=$(jq -r '.resourceVersion' <<<"${result}")
  mcp_success get_mock_config "$(jq -cn --arg id "${config_mock}" '{mockId:$id}')" >/dev/null
  result=$(mcp_success list_mocks '{"limit":200}')
  assert_jq "${result}" ".mocks | any(.mockId == \"${config_mock}\" and .hasSavedConfig == true)" \
    "Saved MCP config was not listed"
  mcp_success start_mock "$(jq -cn --arg id "${config_mock}" '{mockId:$id}')" >/dev/null
  poll_mcp_success list_stubs "$(jq -cn --arg id "${config_mock}" '{mockId:$id,limit:1}')" >/dev/null
  verify_wiremock_workload_identity "${config_mock}"
  result=$(mcp_success delete_mock_config "$(jq -cn --arg id "${config_mock}" --arg rv "${rv}" \
    '{mockId:$id,resourceVersion:$rv,applyMode:"restartActive"}')")
  assert_jq "${result}" ".mockId == \"${config_mock}\" and .deleted == true" \
    "Active saved-config deletion rejected the retained effective mock row"
  mcp_success stop_mock "$(jq -cn --arg id "${config_mock}" '{mockId:$id}')" >/dev/null

  log "Checking config replication, server validation, and conflict details."
  api_request GET /__fleet/api/config
  [[ "${api_status}" == 200 ]] || fail "GET config returned ${api_status}: ${api_body}"
  rv=$(jq -r '.resourceVersion' <<<"${api_body}")
  mutation=$(jq -cn --arg rv "${rv}" '{resourceVersion:$rv,options:["--verbose"],resources:null,applyMode:"futureOnly"}')
  api_request PUT "/__fleet/api/config/${main_mock}" "${mutation}"
  [[ "${api_status}" == 200 ]] || fail "Valid config update returned ${api_status}: ${api_body}"
  assert_jq "${api_body}" ".apply.mockId == \"${main_mock}\" and .apply.mode == \"futureOnly\" and .apply.lifecycle == \"STOPPED\"" \
    "Config mutation did not return lifecycle apply metadata"
  current_rv=$(jq -r '.config.resourceVersion' <<<"${api_body}")
  verify_api_replicas "${main_mock}" --verbose

  invalid_rv=${current_rv}
  mutation=$(jq -cn --arg rv "${invalid_rv}" '{resourceVersion:$rv,options:["--not-advertised"],resources:null,applyMode:"futureOnly"}')
  api_request PUT "/__fleet/api/config/${main_mock}" "${mutation}"
  [[ "${api_status}" == 400 ]] || fail "Invalid config returned ${api_status}, expected 400: ${api_body}"
  assert_jq "${api_body}" '.code == "INVALID_OPTIONS" and .stateMayHaveChanged == false' \
    "Invalid config did not return ApiError"
  api_request GET /__fleet/api/config
  assert_jq "${api_body}" ".resourceVersion == \"${invalid_rv}\" and (.mocks[] | select(.mockId == \"${main_mock}\") | .user.options == [\"--verbose\"])" \
    "Invalid config mutated persisted state"

  mutation=$(jq -cn --arg rv "${invalid_rv}" '{resourceVersion:$rv,options:["--verbose","--disable-banner"],resources:null,applyMode:"futureOnly"}')
  api_request PUT "/__fleet/api/config/${main_mock}" "${mutation}"
  [[ "${api_status}" == 200 ]] || fail "Second valid config update failed: ${api_body}"
  current_rv=$(jq -r '.config.resourceVersion' <<<"${api_body}")
  api_request PUT "/__fleet/api/config/${main_mock}" "${mutation}"
  [[ "${api_status}" == 409 ]] || fail "Stale config update returned ${api_status}, expected 409: ${api_body}"
  assert_jq "${api_body}" ".code == \"CONFIG_CONFLICT\" and .details.expectedVersion == \"${invalid_rv}\" and .details.currentVersion == \"${current_rv}\"" \
    "Config conflict omitted reconciliation versions"

  log "Restarting one API replica and checking fresh-replica config."
  restart_one_api_replica
  verify_api_replicas "${main_mock}" --disable-banner

  log "Checking deterministic cold-start retry and STARTING cleanup."
  api_request DELETE "/__fleet/api/mocks/${cold_mock}"
  [[ "${api_status}" == 200 ]] || fail "Cold mock cleanup returned ${api_status}: ${api_body}"
  assert_jq "${api_body}" '.status == "STOPPED"' "Cold mock did not begin from STOPPED"
  api_request GET /__fleet/api/mocks
  assert_jq "${api_body}" "[.[] | select(.mockId == \"${cold_mock}\" and .status != \"STOPPED\")] | length == 0" \
    "Cold mock still had an active lifecycle before the WireMock tool call"

  api_request GET /__fleet/api/config
  rv=$(jq -r '.resourceVersion' <<<"${api_body}")
  mutation=$(jq -cn --arg rv "${rv}" \
    '{resourceVersion:$rv,options:[],resources:{requests:{cpu:"4"},limits:{cpu:"4"}},applyMode:"futureOnly"}')
  api_request PUT "/__fleet/api/config/${cold_mock}" "${mutation}"
  [[ "${api_status}" == 200 ]] || fail "Cold-start delay config returned ${api_status}: ${api_body}"
  assert_jq "${api_body}" '.apply.lifecycle == "STOPPED"' "Cold-start delay config activated the stopped mock"
  poll_until "cold-start delay config on both API replicas" \
    api_replicas_have_cpu_request "${cold_mock}" 4

  result=$(mcp_tool_raw list_stubs "$(jq -cn --arg id "${cold_mock}" '{mockId:$id,limit:10}')")
  assert_mock_starting_contract "${result}" "${cold_mock}"

  api_request GET /__fleet/api/config
  rv=$(jq -r '.resourceVersion' <<<"${api_body}")
  mutation=$(jq -cn --arg rv "${rv}" '{resourceVersion:$rv,options:[],resources:null,applyMode:"restartActive"}')
  api_request PUT "/__fleet/api/config/${cold_mock}" "${mutation}"
  [[ "${api_status}" == 200 ]] || fail "Cold-start delay release returned ${api_status}: ${api_body}"
  assert_jq "${api_body}" '.apply.lifecycle == "STARTING"' "Cold mock was not restarted with schedulable resources"
  result=$(poll_mcp_success list_stubs "$(jq -cn --arg id "${cold_mock}" '{mockId:$id,limit:10}')")
  assert_jq "${result}" ".mockId == \"${cold_mock}\" and (.stubs | type == \"array\")" "Cold mock never became usable"

  api_request POST "/__fleet/api/mocks/${cleanup_mock}/start"
  [[ "${api_status}" == 202 || "${api_status}" == 200 ]] || fail "Cleanup mock did not start: ${api_body}"
  api_request DELETE "/__fleet/api/mocks/${cleanup_mock}"
  [[ "${api_status}" == 200 ]] || fail "Deleting starting mock failed: ${api_body}"
  assert_jq "${api_body}" '.status == "STOPPED"' "DELETE did not stop starting mock"
  api_request DELETE "/__fleet/api/mocks/${cleanup_mock}"
  assert_jq "${api_body}" '.status == "STOPPED"' "Repeated DELETE was not idempotent"

  log "Checking recoverable persistent update and restart survival."
  config=$(cat "${fixture_dir}/persistent-stub.json")
  result=$(create_stub_when_ready "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')")
  stub_id=$(jq -r '.stub.id // .stub.uuid' <<<"${result}")
  [[ -n "${stub_id}" && "${stub_id}" != null ]] || fail "create_stub did not return a stub ID: ${result}"
  mcp_success persist_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${stub_id}" '{mockId:$id,stubId:$stub}')" >/dev/null
  config=$(cat "${fixture_dir}/persistent-stub-updated.json")
  result=$(mcp_success update_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${stub_id}" --argjson mapping "${config}" '{mockId:$id,stubId:$stub,mapping:$mapping}')")
  assert_jq "${result}" '.stub.persistent == true and .stub.response.body == "persisted-v2"' \
    "Persistent update did not preserve persistence and replacement"
  mcp_success unpersist_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${stub_id}" '{mockId:$id,stubId:$stub}')" >/dev/null
  mcp_success persist_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${stub_id}" '{mockId:$id,stubId:$stub}')" >/dev/null

  config=$(cat "${fixture_dir}/persistent-stub-deleted.json")
  result=$(mcp_success create_stub "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')")
  deleted_stub_id=$(jq -r '.stub.id // .stub.uuid' <<<"${result}")
  [[ -n "${deleted_stub_id}" && "${deleted_stub_id}" != null ]] \
    || fail "Persistent deletion setup did not return a stub ID: ${result}"
  mcp_success persist_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${deleted_stub_id}" '{mockId:$id,stubId:$stub}')" >/dev/null
  mcp_success delete_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${deleted_stub_id}" '{mockId:$id,stubId:$stub}')" >/dev/null

  mcp_success stop_mock "$(jq -cn --arg id "${main_mock}" '{mockId:$id}')" >/dev/null
  result=$(poll_mcp_success list_stubs "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:200}')")
  assert_persistent_restart_state "${result}" "${stub_id}" "${deleted_stub_id}"
  poll_mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/persistent"}')" >"${work_dir}/persistent-response"
  result=$(cat "${work_dir}/persistent-response")
  assert_jq "${result}" '.response.status == 200 and .response.body.encoding == "utf8" and .response.body.data == "persisted-v2"' \
    "Persistent update did not survive restart"

  log "Checking body files and encoded byte contracts."
  body_file_pod=$(ready_wiremock_pod_name "${main_mock}") \
    || fail "Unable to identify the ready WireMock pod before the body-file write."
  body_args=$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"utf8.txt",body:{encoding:"utf8",data:"hello",sizeBytes:5}}')
  mcp_success put_body_file "${body_args}" >/dev/null
  result=$(poll_mcp_success get_body_file "$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"utf8.txt"}')")
  assert_jq "${result}" '.body == {encoding:"utf8",data:"hello",sizeBytes:5}' "UTF-8 body file did not round-trip"
  wiremock_pod_was_replaced "${main_mock}" "${body_file_pod}" \
    || fail "Persistent body-file write did not replace the WireMock pod."
  body_file_pod=$(ready_wiremock_pod_name "${main_mock}") \
    || fail "Unable to identify the ready WireMock pod before the body-file overwrite."
  body_args=$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"utf8.txt",body:{encoding:"utf8",data:"world!",sizeBytes:6}}')
  mcp_success put_body_file "${body_args}" >/dev/null
  result=$(poll_mcp_success get_body_file "$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"utf8.txt"}')")
  assert_jq "${result}" '.body == {encoding:"utf8",data:"world!",sizeBytes:6}' \
    "UTF-8 body-file overwrite did not replace the stored bytes"
  wiremock_pod_was_replaced "${main_mock}" "${body_file_pod}" \
    || fail "Persistent body-file overwrite did not replace the WireMock pod."
  body_file_pod=$(ready_wiremock_pod_name "${main_mock}") \
    || fail "Unable to identify the ready WireMock pod before the binary body-file write."
  body_args=$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"binary.bin",body:{encoding:"base64",data:"AAE=",sizeBytes:2}}')
  mcp_success put_body_file "${body_args}" >/dev/null
  result=$(poll_mcp_success get_body_file "$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"binary.bin"}')")
  assert_jq "${result}" '.body == {encoding:"base64",data:"AAE=",sizeBytes:2}' "Binary body file did not round-trip"
  wiremock_pod_was_replaced "${main_mock}" "${body_file_pod}" \
    || fail "Repeated persistent body-file write did not replace the WireMock pod."
  result=$(mcp_success list_body_files "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:1}')")
  assert_jq "${result}" '(.files | length) == 1 and .page.hasMore == true and (.page.nextCursor | type == "string")' \
    "Body-file cursor page was incomplete"

  log "Checking matched/missed analysis, redaction, and Admin-path guards."
  config='{"request":{"method":"GET","urlPath":"/matched"},"response":{"status":204}}'
  mcp_success create_stub "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')" >/dev/null
  config='{"request":{"method":"GET","urlPath":"/never-hit"},"response":{"status":204}}'
  result=$(mcp_success create_stub "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')")
  unmatched_stub_id=$(jq -r '.stub.id // .stub.uuid' <<<"${result}")
  config='{"request":{"method":"GET","urlPath":"/multi-header"},"response":{"status":200,"headers":{"X-Duplicate-Response":["first","second"]}}}'
  mcp_success create_stub "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')" >/dev/null
  result=$(mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/multi-header",headers:{"X-Duplicate":["one","two"]}}')")
  assert_jq "${result}" '.response.headers | to_entries | map(select((.key | ascii_downcase) == "x-duplicate-response"))[0].value == ["first","second"]' \
    "Fleet Proxy replaced duplicate response headers"
  result=$(mcp_success find_requests "$(jq -cn --arg id "${main_mock}" '{mockId:$id,requestPattern:{urlPath:"/multi-header"},limit:10}')")
  assert_jq "${result}" '.requests[0].request.headers."X-Duplicate" == ["one","two"] or .requests[0].headers."X-Duplicate" == ["one","two"]' \
    "Fleet Proxy replaced duplicate request headers"
  mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/matched",headers:{Authorization:"Bearer secret",Cookie:"session=secret-cookie"}}')" >/dev/null
  mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/missed",headers:{Authorization:"Bearer secret",Cookie:"session=secret-cookie"}}')" >/dev/null
  result=$(mcp_success count_requests "$(jq -cn --arg id "${main_mock}" '{mockId:$id,requestPattern:{urlPath:"/matched"}}')")
  assert_jq "${result}" '.count >= 1' "Matched request count was empty"
  result=$(mcp_success list_unmatched_requests "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:50}')")
  assert_jq "${result}" '[.requests[] | select(.request.url == "/missed" or .url == "/missed")] | length >= 1' \
    "Missed request was absent from unmatched analysis"
  result=$(mcp_success list_unmatched_stubs "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:50}')")
  assert_jq "${result}" ".stubs | any((.id // .uuid) == \"${unmatched_stub_id}\")" \
    "Never-hit stub was absent from unmatched-stub analysis"
  result=$(mcp_success find_requests "$(jq -cn --arg id "${main_mock}" '{mockId:$id,requestPattern:{urlPathPattern:"/(matched|missed)"},limit:50}')")
  assert_jq "${result}" '.. | objects | to_entries[]? | select((.key | ascii_downcase) == "authorization") | .value | if type == "array" then index("[REDACTED]") != null else . == "[REDACTED]" end' \
    "Sensitive journal headers were not redacted"
  [[ "${result}" != *secret-cookie* ]] || fail "Parsed cookies leaked from request search results"
  result=$(mcp_success get_near_misses "$(jq -cn --arg id "${main_mock}" '{mockId:$id,requestPattern:{urlPath:"/almost-matched"},limit:50}')")
  [[ "${result}" != *secret-cookie* ]] || fail "Parsed cookies leaked from near-miss results"

  result=$(mcp_success list_stubs "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:1}')")
  assert_jq "${result}" '.page.hasMore == true and (.page.nextCursor | type == "string")' \
    "Stub cursor did not expose a continuation"
  local first_stub_cursor first_stub_page_id second_stub_page_id
  first_stub_cursor=$(jq -r '.page.nextCursor' <<<"${result}")
  first_stub_page_id=$(jq -r '.stubs[0].id // .stubs[0].uuid' <<<"${result}")
  result=$(mcp_success list_stubs "$(jq -cn --arg id "${main_mock}" --arg cursor "${first_stub_cursor}" '{mockId:$id,limit:1,cursor:$cursor}')")
  second_stub_page_id=$(jq -r '.stubs[0].id // .stubs[0].uuid' <<<"${result}")
  [[ "${first_stub_page_id}" != "${second_stub_page_id}" ]] || fail "Stub cursor repeated the previous mapping"
  for path in '/__admin' '/%5f%5fadmin/mappings' '/x/../__admin/requests'; do
    mcp_expect_error send_request "$(jq -cn --arg id "${main_mock}" --arg path "${path}" '{mockId:$id,method:"GET",path:$path}')" INVALID_ARGUMENT >/dev/null
  done

  log "Checking recording candidates, explicit zero matches, and SSRF policy."
  mcp_expect_error start_recording "$(jq -cn --arg id "${main_mock}" '{mockId:$id,recording:{targetBaseUrl:"http://127.0.0.1:8080"}}')" INVALID_ARGUMENT >/dev/null
  local recording_target="http://recording-target.${namespace}.svc.cluster.local:5678"
  result=$(mcp_success start_recording "$(jq -cn --arg id "${main_mock}" --arg target "${recording_target}" '{mockId:$id,recording:{targetBaseUrl:$target}}')")
  assert_jq "${result}" '.status.status == "Recording" or .status.status == "recording" or .status.recording == true' \
    "Recorder did not report active status"
  mcp_success get_recording_status "$(jq -cn --arg id "${main_mock}" '{mockId:$id}')" >/dev/null
  mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/record-me",headers:{Authorization:"Bearer secret",Cookie:"session=secret-cookie"}}')" >/dev/null
  result=$(mcp_success stop_recording "$(jq -cn --arg id "${main_mock}" '{mockId:$id}')")
  assert_jq "${result}" '.candidateCount >= 1 and .matchedRequests == true and (.candidateIds | length) == .candidateCount' \
    "Recording stop omitted candidate results"
  candidate_id=$(jq -r '.candidateIds[0]' <<<"${result}")
  mcp_success persist_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${candidate_id}" '{mockId:$id,stubId:$stub}')" >/dev/null
  result=$(mcp_success get_stub "$(jq -cn --arg id "${main_mock}" --arg stub "${candidate_id}" '{mockId:$id,stubId:$stub}')")
  assert_jq "${result}" '[.. | objects | keys[] | ascii_downcase | select(. == "authorization" or . == "cookie" or . == "set-cookie")] | length == 0' \
    "Recorded candidate retained sensitive headers"
  result=$(mcp_success snapshot_requests "$(jq -cn --arg id "${main_mock}" '{mockId:$id,snapshot:{filters:{urlPath:"/never-requested"}}}')")
  assert_jq "${result}" '.candidateIds == [] and .candidateCount == 0 and .matchedRequests == false' \
    "Zero-match snapshot was not explicit"

  mcp_success reset_request_journal "$(jq -cn --arg id "${main_mock}" '{mockId:$id}')" >/dev/null
  mcp_success list_scenarios "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:50}')" >/dev/null
  mcp_success reset_scenarios "$(jq -cn --arg id "${main_mock}" '{mockId:$id}')" >/dev/null
  if [[ "${retain_success}" != true ]]; then
    mcp_success delete_body_file "$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"utf8.txt",force:true}')" >/dev/null
    mcp_success delete_body_file "$(jq -cn --arg id "${main_mock}" '{mockId:$id,fileName:"binary.bin",force:true}')" >/dev/null
  fi

  log "Checking eager restart lifecycle."
  api_request GET /__fleet/api/config
  rv=$(jq -r '.resourceVersion' <<<"${api_body}")
  mutation=$(jq -cn --arg rv "${rv}" '{resourceVersion:$rv,options:["--verbose"],resources:null,applyMode:"restartActive"}')
  api_request PUT "/__fleet/api/config/${main_mock}" "${mutation}"
  [[ "${api_status}" == 200 ]] || fail "restartActive config update failed: ${api_body}"
  assert_jq "${api_body}" '.apply.lifecycle == "STARTING"' "Active config restart did not return STARTING"
  poll_mcp_success list_stubs "$(jq -cn --arg id "${main_mock}" '{mockId:$id,limit:10}')" >/dev/null

  log "Checking terminal startup failure and FAILED cleanup."
  kubectl set env deployment/"${release}-api" --namespace "${namespace}" \
    MOCK_FLEET_WIREMOCK_IMAGE=mock-fleet-e2e/missing:3.13.2 \
    MOCK_FLEET_WIREMOCK_IMAGE_PULL_POLICY=Never >/dev/null
  kubectl rollout status deployment/"${release}-api" --namespace "${namespace}" --timeout="${timeout_seconds}s"
  start_api_port_forward
  api_request POST "/__fleet/api/mocks/${failed_mock}/start"
  [[ "${api_status}" == 202 || "${api_status}" == 503 ]] || fail "Failure probe start returned ${api_status}: ${api_body}"
  poll_until "${failed_mock} FAILED lifecycle" api_mock_failed "${failed_mock}"
  api_request DELETE "/__fleet/api/mocks/${failed_mock}"
  assert_jq "${api_body}" '.status == "STOPPED"' "DELETE did not clean up FAILED mock"

  kubectl set env deployment/"${release}-api" --namespace "${namespace}" \
    "MOCK_FLEET_WIREMOCK_IMAGE=${wiremock_image}" \
    MOCK_FLEET_WIREMOCK_IMAGE_PULL_POLICY=IfNotPresent >/dev/null
  kubectl rollout status deployment/"${release}-api" --namespace "${namespace}" --timeout="${timeout_seconds}s"
  start_api_port_forward

  if [[ "${retain_success}" == true ]]; then
    local warned_mock="warn-${run_id:0:16}"
    api_request GET /__fleet/api/config
    rv=$(jq -r '.resourceVersion' <<<"${api_body}")
    mutation=$(jq -cn --arg rv "${rv}" '{resourceVersion:$rv,options:["--disable-optimize-xml-factories-loading"],resources:null,applyMode:"futureOnly"}')
    api_request PUT "/__fleet/api/config/${warned_mock}" "${mutation}"
    [[ "${api_status}" == 200 ]] || fail "Unable to seed retained warned configuration: ${api_body}"
    config='{"scenarioName":"retained-checkout","requiredScenarioState":"Started","newScenarioState":"Viewed","request":{"method":"GET","urlPath":"/retained-scenario"},"response":{"status":200,"body":"scenario-viewed"}}'
    create_stub_when_ready "$(jq -cn --arg id "${main_mock}" --argjson mapping "${config}" '{mockId:$id,mapping:$mapping}')" >/dev/null
    poll_mcp_success send_request "$(jq -cn --arg id "${main_mock}" '{mockId:$id,method:"GET",path:"/retained-scenario"}')" >/dev/null
    log "Retained profile: namespace=${namespace} release=${release} activeMock=${main_mock} stoppedMock=${warned_mock}"
    log "Inspect after port-forwarding: dashboard service/${release}-dash:80 and MCP service/${release}-mcp:80"
  fi

  log "All live cluster contract assertions passed."
}

self_test() {
  bash -n "$0"
  [[ -s "${fixture_dir}/persistent-stub.json" && -s "${fixture_dir}/persistent-stub-updated.json" \
    && -s "${fixture_dir}/persistent-stub-deleted.json" ]] \
    || fail "Cluster fixtures are missing."
  [[ "${namespace}" == mock-fleet-e2e-* && "${bucket}" == mock-fleet-e2e-* && "${namespace}" != "${bucket}" ]] \
    || fail "Run resources are not isolated."
  local helm_command
  helm_command=$(
    kubectl() { printf '10.0.0.1\n'; }
    helm() { printf '%s\n' "$*"; }
    helm_deploy wiremock/wiremock:3.13.2-2 IfNotPresent
  )
  for mount_option in \
    'storage.s3.mountOptions[3]=allow-overwrite' \
    'storage.s3.mountOptions[4]=metadata-ttl minimal'; do
    grep -Fq -- "${mount_option}" <<<"${helm_command}" \
      || fail "Live S3 storage is missing its multi-writer mount option: ${mount_option}"
  done
  log "S3 multi-writer mount contract passed."
  for updater_setting in \
    'wiremock.versionUpdater.enabled=true' \
    'wiremock.versionUpdater.registry.url=http://wiremock-registry.' \
    'wiremock.versionUpdater.registry.repository=wiremock/wiremock' \
    'wiremock.versionUpdater.minorLines=2' \
    'wiremock.versionUpdater.image.repository=ghcr.io/letsrokk/mock-fleet/wiremock-updater'; do
    grep -Fq -- "${updater_setting}" <<<"${helm_command}" \
      || fail "Live Helm install omitted updater setting: ${updater_setting}"
  done
  log "Updater Helm install contract passed."
  local registry_manifest
  registry_manifest=$(
    kubectl() {
      if [[ "$*" == 'apply -f -' ]]; then
        cat
      elif [[ "$*" == 'rollout status deployment/wiremock-registry '* ]]; then
        return 0
      fi
    }
    create_fake_registry
  )
  jq -e '
    select(.kind == "List")
    | [.items[].kind] == ["Deployment", "Service"] and
      .items[0].spec.template.spec.securityContext.runAsNonRoot == true and
      .items[0].spec.template.spec.containers[0].securityContext.readOnlyRootFilesystem == true and
      (.items[0].spec.template.spec.containers[0].args | join(" ") | contains("3.13.2-2") and contains("3.12.1-2"))
  ' >/dev/null <<<"${registry_manifest}" \
    || fail "Fake Registry V2 fixture does not expose two versions under restricted PSA."
  log "Fake Registry V2 fixture contract passed."
  if ! catalog_has_reconciled_versions '{"data":{"defaultVersion":"3.13.2","selectable.3.13.2":"wiremock/wiremock:3.13.2-2","selectable.3.12.1":"wiremock/wiremock:3.12.1-2"}}'; then
    fail "Updater reconciliation rejected the expected two-version catalog."
  fi
  if catalog_has_reconciled_versions '{"data":{"defaultVersion":"3.13.2","selectable.3.13.2":"wiremock/wiremock:3.13.2-2"}}'; then
    fail "Updater reconciliation accepted a catalog missing its second WireMock version."
  fi
  log "Two-version catalog reconciliation contract passed."
  if ! (
    kubectl() {
      local arg overrides=""
      if [[ "$1" == run ]]; then
        for arg in "$@"; do
          [[ "${arg}" == --overrides=* ]] && overrides=${arg#--overrides=}
        done
        jq -e '
          .spec.securityContext.runAsNonRoot == true and
          .spec.securityContext.seccompProfile.type == "RuntimeDefault" and
          .spec.containers[0].securityContext.runAsUser == 1000 and
          .spec.containers[0].securityContext.allowPrivilegeEscalation == false and
          .spec.containers[0].securityContext.capabilities.drop == ["ALL"] and
          .spec.containers[0].resources.requests.cpu == "25m" and
          .spec.containers[0].resources.limits.memory == "256Mi"
        ' >/dev/null <<<"${overrides}"
      elif [[ "$1" == wait ]]; then
        return 0
      elif [[ "$1" == logs ]]; then
        return 0
      fi
    }
    run_s3_cli self-test sts get-caller-identity
  ); then
    fail "S3 helper pod does not satisfy restricted PSA and quota resources."
  fi
  log "S3 helper restricted PSA contract passed."
  local dry_output
  dry_output=$(MOCK_FLEET_E2E_RUN_ID=self-test "$0" --dry-run)
  grep -Fq 'No cluster changes were made.' <<<"${dry_output}" || fail "Dry-run did not confirm no changes."
  local marker_command
  marker_command=$(
    run_s3_cli() { printf '%s\n' "$*"; }
    write_bucket_ownership_marker
  )
  grep -Fq -- '--body /etc/hosts' <<<"${marker_command}" \
    || fail "Ownership marker upload does not pass a regular file to AWS CLI."
  log "Ownership marker upload contract passed."
  local recording_command
  recording_command=$(
    kubectl() {
      if [[ "$*" == 'create deployment recording-target '* ]]; then
        [[ "$*" == *'-- /http-echo --listen=:5678 --text=recorded'* ]] || return 1
        printf '%s\n' '{"spec":{"template":{"spec":{"containers":[{"name":"http-echo"}]}}}}'
      else
        cat
      fi
    }
    create_recording_target
  )
  jq -e '
    .spec.template.spec.securityContext.runAsNonRoot == true and
    .spec.template.spec.securityContext.seccompProfile.type == "RuntimeDefault" and
    .spec.template.spec.containers[0].securityContext.allowPrivilegeEscalation == false and
    .spec.template.spec.containers[0].securityContext.capabilities.drop == ["ALL"]
  ' >/dev/null <<<"${recording_command}" \
    || fail "Recording target does not satisfy restricted PSA."
  log "Recording target command contract passed."
  local psa_command
  psa_command=$(
    kubectl() {
      if [[ "$*" == 'version -o json' ]]; then
        printf '%s\n' '{"serverVersion":{"major":"1","minor":"36+"}}'
      else
        printf '%s\n' "$*"
      fi
    }
    label_namespace_for_restricted_psa self-test-namespace
  )
  for psa_label in \
    'pod-security.kubernetes.io/enforce=restricted' \
    'pod-security.kubernetes.io/enforce-version=v1.36' \
    'pod-security.kubernetes.io/audit=restricted' \
    'pod-security.kubernetes.io/audit-version=v1.36' \
    'pod-security.kubernetes.io/warn=restricted' \
    'pod-security.kubernetes.io/warn-version=v1.36'; do
    grep -Fq "${psa_label}" <<<"${psa_command}" \
      || fail "Restricted PSA setup omitted ${psa_label}."
  done
  log "Restricted PSA namespace label contract passed."
  local admission_matrix
  admission_matrix=$(
    admission_policy_name=self-test-policy
    server_dry_run_admission_fixture() { printf '%s %s\n' "$1" "$2"; }
    verify_admission_dry_runs
  )
  for expected_fixture in \
    'accepted accepted' \
    'accepted-workload-identity accepted' \
    'accepted-eks-pod-identity accepted' \
    'privileged rejected' \
    'hostpath rejected' \
    'wrong-image rejected' \
    'wrong-service-account rejected' \
    'label-spoofing rejected' \
    'missing-limits rejected' \
    'excessive-limits rejected' \
    'alternate-sidecar rejected' \
    'identity-alternate-mount rejected' \
    'identity-init-subpath rejected' \
    'native-init-sidecar rejected' \
    'pod-apparmor-unconfined rejected' \
    'container-apparmor-unconfined rejected' \
    'init-apparmor-unconfined rejected' \
    'deprecated-apparmor-annotation rejected' \
    'pod-selinux-user rejected' \
    'container-selinux-type rejected' \
    'init-selinux-role rejected' \
    'container-procmount-unmasked rejected' \
    'init-procmount-unmasked rejected' \
    'missing-init-resources rejected' \
    'wrong-run-as-user rejected' \
    'eks-label-without-token rejected' \
    'eks-token-without-label rejected' \
    'eks-label-wrong-value rejected' \
    'extra-unrelated-label rejected' \
    'eks-nonstandard-token-path rejected' \
    'eks-alternate-volume-name rejected' \
    'eks-dual-irsa-mount rejected' \
    'eks-second-mount rejected' \
    'eks-duplicate-full-uri rejected' \
    'eks-duplicate-token-file rejected' \
    'irsa-duplicate-role-env rejected' \
    'mixed-eks-irsa-identity rejected' \
    'eks-env-without-label rejected' \
    'irsa-env-without-volume rejected' \
    'second-irsa-identity rejected' \
    'unconfigured-custom-audience-irsa rejected'; do
    grep -Fxq "${expected_fixture}" <<<"${admission_matrix}" \
      || fail "Admission dry-run matrix omitted ${expected_fixture}."
  done
  log "Admission dry-run fixture contract passed."
  if ! (
    kubectl() {
      case "$*" in
        *'auth can-i create pods '*) printf 'yes\n' ;;
        *'auth can-i patch configmap/'*'-wiremock-user-config '*) printf 'yes\n' ;;
        *'auth can-i '*) printf 'no\n' ;;
        *) return 1 ;;
      esac
    }
    verify_api_rbac
  ); then
    fail "API RBAC can-i contract rejected the minimized role."
  fi
  log "API RBAC can-i contract passed."
  if ! (
    kubectl() {
      case "$*" in
        *'auth can-i get configmap/'*'-wiremock-config '*) printf 'yes\n' ;;
        *'auth can-i get configmap/'*'-wiremock-user-config '*) printf 'yes\n' ;;
        *'auth can-i get configmap/'*'-wiremock-version-catalog '*) printf 'yes\n' ;;
        *'auth can-i update configmap/'*'-wiremock-version-catalog '*) printf 'yes\n' ;;
        *'auth can-i patch configmap/'*'-wiremock-version-catalog '*) printf 'yes\n' ;;
        *'auth can-i '*) printf 'no\n' ;;
        *) return 1 ;;
      esac
    }
    verify_updater_rbac
  ); then
    fail "Updater RBAC can-i contract rejected the minimized role."
  fi
  log "Updater RBAC can-i contract passed."
  local identity_pods
  identity_pods=$(jq -cn --arg service_account "${release}-wiremock" '{items:[{spec:{
    serviceAccountName:$service_account,
    automountServiceAccountToken:false,
    volumes:[{name:"aws-iam-token",projected:{sources:[{serviceAccountToken:{audience:"sts.amazonaws.com"}}]}}]
  }}]}')
  if ! (
    kubectl() { printf '%s\n' "${identity_pods}"; }
    verify_wiremock_workload_identity self-test
  ); then
    fail "Dedicated WireMock identity rejected an audience-bound workload token."
  fi
  if pods_have_no_general_api_token <<<"$(jq -c '.items[0].spec.volumes[0].projected.sources[0].serviceAccountToken.audience = "https://kubernetes.default.svc"' <<<"${identity_pods}")"; then
    fail "Tokenless workload assertion accepted a general Kubernetes API token."
  fi
  log "Tokenless workload identity contract passed."
  local fixed_workload_pods
  fixed_workload_pods='{"items":[
    {"metadata":{"labels":{"app.kubernetes.io/component":"proxy"}},"spec":{"automountServiceAccountToken":false}},
    {"metadata":{"labels":{"app.kubernetes.io/component":"dash"}},"spec":{"automountServiceAccountToken":false}},
    {"metadata":{"labels":{"app.kubernetes.io/component":"mcp"}},"spec":{"automountServiceAccountToken":false}}
  ]}'
  if ! (
    kubectl() { printf '%s\n' "${fixed_workload_pods}"; }
    verify_fixed_workloads_are_tokenless
  ); then
    fail "Fixed workload presence rejected the E2E profile's enabled components."
  fi
  if (
    kubectl() { printf '%s\n' '{"items":[]}' ; }
    verify_fixed_workloads_are_tokenless >/dev/null 2>&1
  ); then
    fail "Fixed workload token assertion accepted a vacuous empty Pod set."
  fi
  log "Fixed workload presence contract passed."
  local parsed_session
  parsed_session=$(printf 'mcp-session-id: self-test-session\r\n' | extract_mcp_session_id)
  [[ "${parsed_session}" == self-test-session ]] \
    || fail "MCP session parsing is not portable across HTTP header casing."
  log "MCP session header contract passed."
  if ! (
    curl() { printf '202'; }
    mcp_session=self-test-session
    mcp_notify '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  ); then
    fail "MCP notification helper rejected the protocol's empty 202 response."
  fi
  if (
    curl() { printf '200'; }
    mcp_session=self-test-session
    mcp_notify '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null 2>&1
  ); then
    fail "MCP notification helper accepted a non-202 response."
  fi
  log "MCP notification response contract passed."
  local option_catalog='{"wireMockVersion":"3.13.2","catalogStatus":"supported","options":[{"name":"--verbose","label":"Verbose","kind":"flag","group":"General","description":"Verbose logging","values":[],"minimum":null,"maximum":null}]}'
  assert_option_catalog_contract "${option_catalog}" "3.13.2"
  local stale_option_catalog='{"wireMock":{"version":"3.13.2","minimumSupportedVersion":"3.0.0","maximumResearchedVersion":"3.13.2"},"optionDefinitions":[]}'
  if ( assert_option_catalog_contract "${stale_option_catalog}" "3.13.2" >/dev/null 2>&1 ); then
    fail "MCP option catalog assertion accepted the retired response shape."
  fi
  log "MCP option catalog response contract passed."
  local ready_replacement='{"items":[
    {"metadata":{"name":"api-new-1"},"status":{"conditions":[{"type":"Ready","status":"True"}]}},
    {"metadata":{"name":"api-new-2"},"status":{"conditions":[{"type":"Ready","status":"True"}]}}
  ]}'
  if ! (
    kubectl() { printf '%s\n' "${ready_replacement}"; }
    api_replacement_ready api-old
  ); then
    fail "API replacement readiness rejected two new ready pods."
  fi
  if (
    kubectl() { printf '%s\n' "${ready_replacement/api-new-1/api-old}"; }
    api_replacement_ready api-old
  ); then
    fail "API replacement readiness accepted the pod selected for deletion."
  fi
  log "API replacement readiness contract passed."
  local wiremock_replacement='{"items":[
    {"metadata":{"name":"wiremock-old","deletionTimestamp":"2026-08-28T00:00:00Z","labels":{"mock-fleet/mock-id":"self-test"}},"status":{"conditions":[{"type":"Ready","status":"True"}]}},
    {"metadata":{"name":"wiremock-new","labels":{"mock-fleet/mock-id":"self-test"}},"status":{"conditions":[{"type":"Ready","status":"True"}]}}
  ]}'
  if ! (
    kubectl() { printf '%s\n' "${wiremock_replacement}"; }
    wiremock_pod_was_replaced self-test wiremock-old
  ); then
    fail "WireMock replacement assertion rejected a new ready pod."
  fi
  if (
    kubectl() { printf '%s\n' "${wiremock_replacement}"; }
    wiremock_pod_was_replaced self-test wiremock-new
  ); then
    fail "WireMock replacement assertion accepted the original ready pod."
  fi
  log "WireMock pod replacement contract passed."
  local api_restart_commands
  api_restart_commands=$(
    kubectl() {
      if [[ "$*" == *jsonpath=* ]]; then
        printf 'api-self-test-pod\n'
      elif [[ "$*" == *'-o json'* ]]; then
        printf '%s\n' "${ready_replacement}"
      else
        printf 'kubectl %s\n' "$*"
      fi
    }
    start_api_port_forward() { printf 'api-port-forward-restarted\n'; }
    restart_one_api_replica
  )
  grep -Fq 'api-port-forward-restarted' <<<"${api_restart_commands}" \
    || fail "API replica restart contract left a stale pod-bound port-forward."
  log "API replica transport restart contract passed."
  local cold_start_create_command
  cold_start_create_command=$(
    poll_mcp_success() { printf '%s\n' "$*"; }
    create_stub_when_ready '{"mockId":"self-test","mapping":{}}'
  )
  grep -Fq 'create_stub {"mockId":"self-test","mapping":{}}' <<<"${cold_start_create_command}" \
    || fail "Cold-start stub creation does not use the retryable MCP polling path."
  log "Cold-start mutation retry contract passed."
  local starting_result='{"isError":true,"structuredContent":{"error":{"code":"MOCK_STARTING","message":"Mock self-test is still starting","retryable":true,"stateMayHaveChanged":false,"details":{"mockId":"self-test","status":"STARTING","retryAfterMs":1000}}}}'
  mock_starting_retry_is_safe "${starting_result}" self-test \
    || fail "Safe MOCK_STARTING result was not eligible for retry."
  if mock_starting_retry_is_safe "$(jq -c '.structuredContent.error.retryable = false' <<<"${starting_result}")" self-test \
      || mock_starting_retry_is_safe "$(jq -c '.structuredContent.error.stateMayHaveChanged = true' <<<"${starting_result}")" self-test \
      || mock_starting_retry_is_safe "$(jq -c 'del(.structuredContent.error.details.status)' <<<"${starting_result}")" self-test \
      || mock_starting_retry_is_safe "${starting_result}" other-mock; then
    fail "Unsafe MOCK_STARTING result was eligible for retry."
  fi
  log "MOCK_STARTING retry safety contract passed."
  assert_mock_starting_contract "${starting_result}" self-test
  if ( assert_mock_starting_contract '{"isError":false}' self-test >/dev/null 2>&1 ); then
    fail "Cold-start assertion accepted a successful WireMock result."
  fi
  log "MOCK_STARTING error contract passed."
  local persistent_result='{"stubs":[{"id":"survivor"},{"id":"other"}]}'
  assert_persistent_restart_state "${persistent_result}" survivor deleted
  if ( assert_persistent_restart_state "${persistent_result}" survivor other >/dev/null 2>&1 ); then
    fail "Persistent restart assertion accepted a deleted stub."
  fi
  log "Persistent restart state contract passed."
  if ( require_absent_bucket_state "$(bucket_probe_state 0 "${bucket}")" >/dev/null 2>&1 ); then
    fail "Bucket probe accepted an accessible existing bucket."
  fi
  log "Existing bucket refusal contract passed."
  if ( require_absent_bucket_state "$(bucket_probe_state 1 "")" >/dev/null 2>&1 ); then
    fail "Bucket probe accepted an ambiguous or forbidden result."
  fi
  log "Ambiguous bucket refusal contract passed."
  local first_bucket second_bucket
  first_bucket=$(bucket_name_for_token "11111111111111111111111111111111")
  second_bucket=$(bucket_name_for_token "22222222222222222222222222222222")
  [[ "${first_bucket}" != "${second_bucket}" && ${#first_bucket} -le 63 && ${#second_bucket} -le 63 ]] \
    || fail "Ownership tokens did not isolate run-specific bucket names."
  log "Ownership-token bucket isolation contract passed."
  empty_bucket_probe_allows_ownership 0 0 \
    || fail "Empty bucket probe rejected an empty bucket."
  if empty_bucket_probe_allows_ownership 0 1 || empty_bucket_probe_allows_ownership 1 0; then
    fail "Empty bucket probe accepted content or an ambiguous read."
  fi
  log "Post-create empty-bucket gate passed."
  ownership_marker_matches_token "${ownership_token}" \
    || fail "Ownership marker rejected this execution."
  if ownership_marker_matches_token "${run_id}"; then
    fail "Ownership marker accepted a run ID without the ownership token."
  fi
  log "Run-ID-only marker refusal contract passed."
  local different_token="0${ownership_token:1}"
  [[ "${different_token}" != "${ownership_token}" ]] || different_token="1${ownership_token:1}"
  if ownership_marker_matches_token "${different_token}"; then
    fail "Ownership marker accepted a different ownership token."
  fi
  log "Ownership marker cleanup gate passed."
  log "Self-test passed."
}

dry_run() {
  cat <<EOF
Run ID:       ${run_id}
Namespace:    ${namespace}
Release:      ${release}
S3 bucket:    ${bucket}
S3 endpoint:  ${s3_endpoint}
CSI driver:   ${s3_provisioner}
StorageClass: ${storage_class}
Routing mode: ${routing_mode}
MCP URL:      http://127.0.0.1:${mcp_port}/__fleet/mcp
API URL:      http://127.0.0.1:${api_port}/__fleet/api
No cluster changes were made.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test) mode=self-test ;;
    --dry-run) mode=dry-run ;;
    --keep) keep=true ;;
    --retain) retain_success=true ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail "Unknown option: $1" ;;
  esac
  shift
done

[[ "${routing_mode}" == PATH || "${routing_mode}" == HOST ]] \
  || fail "MOCK_FLEET_E2E_ROUTING_MODE must be PATH or HOST."

case "${mode}" in
  self-test) self_test; exit 0 ;;
  dry-run) dry_run; exit 0 ;;
esac

for command in minikube kubectl helm curl jq awk sed; do
  require_command "${command}"
done
[[ -n "${run_id}" ]] || fail "MOCK_FLEET_E2E_RUN_ID contains no valid identifier characters."
[[ -n "${s3_access_key}" ]] || fail "Set MOCK_FLEET_E2E_S3_ACCESS_KEY for the isolated SeaweedFS bucket."
[[ -n "${s3_secret_key}" ]] || fail "Set MOCK_FLEET_E2E_S3_SECRET_KEY for the isolated SeaweedFS bucket."
[[ $(minikube status --format='{{.Host}}' 2>/dev/null || true) == Running ]] \
  || fail "Minikube is not running. Start the intended profile before this opt-in suite."
[[ $(kubectl config current-context) == minikube* ]] \
  || fail "Current kubectl context is not Minikube. Refusing to create cluster resources."
kubectl get csidriver "${s3_provisioner}" >/dev/null 2>&1 \
  || fail "CSI driver ${s3_provisioner} is unavailable. Install the SeaweedFS-compatible S3 CSI driver."
kubectl get storageclass "${storage_class}" >/dev/null 2>&1 \
  || fail "StorageClass ${storage_class} is unavailable. Install the SeaweedFS S3 StorageClass."
kubectl get namespace "${namespace}" >/dev/null 2>&1 \
  && fail "Namespace ${namespace} already exists. Choose a unique MOCK_FLEET_E2E_RUN_ID."

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/mock-fleet-e2e.XXXXXX")
trap cleanup EXIT INT TERM
kubectl create namespace "${namespace}" >/dev/null
namespace_created=true
label_namespace_for_restricted_psa "${namespace}" >/dev/null

log "Creating isolated SeaweedFS bucket ${bucket}."
bucket_listing=""
bucket_probe_status=0
if bucket_listing=$(run_s3_cli inspect s3api list-buckets --query 'Buckets[].Name' --output text); then
  bucket_probe_status=0
else
  bucket_probe_status=$?
fi
require_absent_bucket_state "$(bucket_probe_state "${bucket_probe_status}" "${bucket_listing}")"
run_s3_cli create s3api create-bucket --bucket "${bucket}" >/dev/null \
  || fail "Unable to create ${bucket} through ${s3_endpoint}. Check SeaweedFS credentials and endpoint DNS."
bucket_created=true
bucket_object_count=""
bucket_empty_probe_status=0
if bucket_object_count=$(run_s3_cli empty-check s3api list-objects-v2 --bucket "${bucket}" \
    --max-keys 1 --query KeyCount --output text); then
  bucket_empty_probe_status=0
else
  bucket_empty_probe_status=$?
fi
empty_bucket_probe_allows_ownership "${bucket_empty_probe_status}" "${bucket_object_count}" \
  || fail "Cannot claim ownership of ${bucket}; the new bucket is not provably empty."
write_bucket_ownership_marker >/dev/null 2>&1 \
  || fail "Unable to write the ownership marker for ${bucket}. Cleanup will refuse to delete it."
bucket_ownership_marker_matches \
  || fail "Unable to verify the ownership marker for ${bucket}. Cleanup will refuse to delete it."

create_recording_target >/dev/null
kubectl expose deployment recording-target --namespace "${namespace}" --port=5678 --target-port=5678 >/dev/null
kubectl rollout status deployment/recording-target --namespace "${namespace}" --timeout="${timeout_seconds}s"
create_fake_registry >/dev/null

log "Installing Mock Fleet into ${namespace}."
helm_deploy "${wiremock_image}" IfNotPresent
kubectl get deployment --namespace "${namespace}" -l 'app.kubernetes.io/component=api' \
  -o jsonpath='{.items[0].status.readyReplicas}' | grep -qx '2' \
  || fail "Fleet API did not reach two ready replicas."
verify_api_rbac
verify_updater_rbac
verify_admission_dry_runs
verify_fixed_workloads_are_tokenless
run_updater_reconciliation

kubectl port-forward --namespace "${namespace}" service/"${release}-mcp" "${mcp_port}:80" \
  >"${work_dir}/mcp-port-forward.log" 2>&1 &
mcp_pf_pid=$!
poll_until "MCP port-forward" curl --silent --output /dev/null "http://127.0.0.1:${mcp_port}/__fleet/mcp"
start_api_port_forward

verify_two_wiremock_versions

initialize_mcp
run_contracts
