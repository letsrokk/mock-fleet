#!/usr/bin/env bash
set -euo pipefail

admission_fixture() {
  local variant=$1
  local namespace=${2:-testing}
  local pod_prefix=${3:-custom-fleet}
  local service_account=${4:-custom-fleet-wiremock}
  local image=${5:-wiremock/wiremock:3.13.2-2}
  local persistent=${6:-false}
  local pvc_name=${7:-custom-fleet-pvc}
  local storage_path=${8:-/mock-fleet}
  local fixture

  fixture=$(jq -cn \
    --arg namespace "${namespace}" \
    --arg pod_prefix "${pod_prefix}" \
    --arg service_account "${service_account}" \
    --arg image "${image}" \
    '{
      apiVersion:"v1",
      kind:"Pod",
      metadata:{
        name:($pod_prefix + "-demo-fixture"),
        namespace:$namespace,
        labels:{
          "app.kubernetes.io/name":"mock-fleet-wiremock",
          "app.kubernetes.io/managed-by":"mock-fleet",
          "mock-fleet/mock-id":"demo"
        }
      },
      spec:{
        serviceAccountName:$service_account,
        automountServiceAccountToken:false,
        restartPolicy:"Never",
        terminationGracePeriodSeconds:5,
        securityContext:{runAsNonRoot:true,seccompProfile:{type:"RuntimeDefault"}},
        containers:[{
          name:"wiremock",
          image:$image,
          imagePullPolicy:"IfNotPresent",
          securityContext:{
            runAsNonRoot:true,
            allowPrivilegeEscalation:false,
            capabilities:{drop:["ALL"]},
            seccompProfile:{type:"RuntimeDefault"}
          },
          ports:[{containerPort:8080}],
          startupProbe:{httpGet:{path:"/__admin/health",port:8080},initialDelaySeconds:1,periodSeconds:1,timeoutSeconds:1,failureThreshold:60},
          readinessProbe:{httpGet:{path:"/__admin/health",port:8080},initialDelaySeconds:1,periodSeconds:1,timeoutSeconds:1,failureThreshold:30},
          livenessProbe:{httpGet:{path:"/__admin/health",port:8080},initialDelaySeconds:10,periodSeconds:10,timeoutSeconds:1,failureThreshold:3},
          resources:{requests:{cpu:"500m",memory:"512Mi"},limits:{cpu:"1",memory:"1Gi"}}
        }]
      }
    }')

  if [[ "${persistent}" == true ]]; then
    fixture=$(jq -c --arg pvc_name "${pvc_name}" --arg storage_path "${storage_path}" '
      .metadata.labels["mock-fleet/mock-id"] as $mock_id
      | .spec.volumes = [{name:"wiremock-mappings",persistentVolumeClaim:{claimName:$pvc_name}}]
      | .spec.containers[0].volumeMounts = [{name:"wiremock-mappings",mountPath:"/home/wiremock",subPath:$mock_id}]
      | .spec.initContainers = [{
          name:"prepare-wiremock-mappings",
          image:"busybox:1.36",
          command:["mkdir","-p",($storage_path + "/" + $mock_id)],
          securityContext:{
            runAsNonRoot:true,
            runAsUser:1000,
            allowPrivilegeEscalation:false,
            capabilities:{drop:["ALL"]},
            seccompProfile:{type:"RuntimeDefault"}
          },
          volumeMounts:[{name:"wiremock-mappings",mountPath:$storage_path}]
        }]
    ' <<<"${fixture}")
  fi

  case "${variant}" in
    accepted) ;;
    accepted-workload-identity|identity-alternate-mount|identity-init-subpath|irsa-duplicate-role-env)
      fixture=$(jq -c '
        .spec.volumes += [{
          name:"aws-iam-token",
          projected:{sources:[{serviceAccountToken:{audience:"sts.amazonaws.com",expirationSeconds:86400,path:"token"}}]}
        }]
        | .spec.containers[0].volumeMounts += [{name:"aws-iam-token",mountPath:"/var/run/secrets/eks.amazonaws.com/serviceaccount",readOnly:true}]
        | .spec.containers[0].env = [
            {name:"AWS_ROLE_ARN",value:"arn:aws:iam::123456789012:role/mock-fleet"},
            {name:"AWS_WEB_IDENTITY_TOKEN_FILE",value:"/var/run/secrets/eks.amazonaws.com/serviceaccount/token"}
          ]
        | if (.spec | has("initContainers")) then
            .spec.initContainers[0].volumeMounts += [{name:"aws-iam-token",mountPath:"/var/run/secrets/eks.amazonaws.com/serviceaccount",readOnly:true}]
            | .spec.initContainers[0].env = [
                {name:"AWS_ROLE_ARN",value:"arn:aws:iam::123456789012:role/mock-fleet"},
                {name:"AWS_WEB_IDENTITY_TOKEN_FILE",value:"/var/run/secrets/eks.amazonaws.com/serviceaccount/token"}
              ]
          else . end
      ' <<<"${fixture}")
      ;;
    accepted-eks-pod-identity|eks-token-without-label|eks-label-wrong-value|eks-nonstandard-token-path|eks-alternate-volume-name|eks-dual-irsa-mount|eks-second-mount|eks-duplicate-full-uri|eks-duplicate-token-file)
      fixture=$(jq -c '
        .metadata.labels["eks.amazonaws.com/pod-identity"] = "enabled"
        | .spec.volumes += [{
          name:"eks-pod-identity-token",
          projected:{sources:[{serviceAccountToken:{audience:"pods.eks.amazonaws.com",expirationSeconds:86400,path:"eks-pod-identity-token"}}]}
        }]
        | .spec.containers[0].volumeMounts += [{name:"eks-pod-identity-token",mountPath:"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",readOnly:true}]
        | .spec.containers[0].env = [
            {name:"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",value:"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"},
            {name:"AWS_CONTAINER_CREDENTIALS_FULL_URI",value:"http://169.254.170.23/v1/credentials"}
          ]
        | if (.spec | has("initContainers")) then
            .spec.initContainers[0].volumeMounts += [{name:"eks-pod-identity-token",mountPath:"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",readOnly:true}]
            | .spec.initContainers[0].env = [
                {name:"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",value:"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"},
                {name:"AWS_CONTAINER_CREDENTIALS_FULL_URI",value:"http://169.254.170.23/v1/credentials"}
              ]
          else . end
      ' <<<"${fixture}")
      ;;
    privileged) fixture=$(jq -c '.spec.containers[0].securityContext.privileged = true' <<<"${fixture}") ;;
    hostpath) fixture=$(jq -c '.spec.volumes += [{name:"host",hostPath:{path:"/"}}]' <<<"${fixture}") ;;
    wrong-image) fixture=$(jq -c '.spec.containers[0].image = "attacker.example/shell:latest"' <<<"${fixture}") ;;
    wrong-service-account) fixture=$(jq -c '.spec.serviceAccountName = "default"' <<<"${fixture}") ;;
    label-spoofing) fixture=$(jq -c '.metadata.labels["app.kubernetes.io/managed-by"] = "attacker"' <<<"${fixture}") ;;
    missing-limits) fixture=$(jq -c 'del(.spec.containers[0].resources.limits)' <<<"${fixture}") ;;
    excessive-limits) fixture=$(jq -c '.spec.containers[0].resources.limits.cpu = "5"' <<<"${fixture}") ;;
    alternate-sidecar)
      fixture=$(jq -c '.spec.containers += [{name:"shell",image:"busybox:1.36",command:["sh"]}]' <<<"${fixture}")
      ;;
    native-init-sidecar) fixture=$(jq -c '.spec.initContainers[0].restartPolicy = "Always"' <<<"${fixture}") ;;
    pod-apparmor-unconfined) fixture=$(jq -c '.spec.securityContext.appArmorProfile = {type:"Unconfined"}' <<<"${fixture}") ;;
    container-apparmor-unconfined) fixture=$(jq -c '.spec.containers[0].securityContext.appArmorProfile = {type:"Unconfined"}' <<<"${fixture}") ;;
    init-apparmor-unconfined) fixture=$(jq -c '.spec.initContainers[0].securityContext.appArmorProfile = {type:"Unconfined"}' <<<"${fixture}") ;;
    deprecated-apparmor-annotation)
      fixture=$(jq -c '.metadata.annotations["container.apparmor.security.beta.kubernetes.io/wiremock"] = "unconfined"' <<<"${fixture}")
      ;;
    pod-selinux-user) fixture=$(jq -c '.spec.securityContext.seLinuxOptions = {user:"system_u",type:"container_t"}' <<<"${fixture}") ;;
    container-selinux-type) fixture=$(jq -c '.spec.containers[0].securityContext.seLinuxOptions = {type:"spc_t"}' <<<"${fixture}") ;;
    init-selinux-role) fixture=$(jq -c '.spec.initContainers[0].securityContext.seLinuxOptions = {role:"system_r",type:"container_init_t"}' <<<"${fixture}") ;;
    container-procmount-unmasked) fixture=$(jq -c '.spec.hostUsers = false | .spec.containers[0].securityContext.procMount = "Unmasked"' <<<"${fixture}") ;;
    init-procmount-unmasked) fixture=$(jq -c '.spec.hostUsers = false | .spec.initContainers[0].securityContext.procMount = "Unmasked"' <<<"${fixture}") ;;
    eks-label-without-token) fixture=$(jq -c '.metadata.labels["eks.amazonaws.com/pod-identity"] = "enabled"' <<<"${fixture}") ;;
    extra-unrelated-label) fixture=$(jq -c '.metadata.labels["attacker.example/unrelated"] = "enabled"' <<<"${fixture}") ;;
    *) printf 'Unknown admission fixture: %s\n' "${variant}" >&2; return 1 ;;
  esac

  case "${variant}" in
    eks-nonstandard-token-path)
      fixture=$(jq -c '
        .spec.volumes[-1].projected.sources[0].serviceAccountToken.path = "token"
        | .spec.containers[0].env |= map(if .name == "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" then .value = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/token" else . end)
        | if (.spec | has("initContainers")) then
            .spec.initContainers[0].env |= map(if .name == "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" then .value = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/token" else . end)
          else . end
      ' <<<"${fixture}")
      ;;
    eks-alternate-volume-name)
      fixture=$(jq -c '
        .spec.volumes[-1].name = "alternate-eks-token"
        | .spec.containers[0].volumeMounts |= map(if .name == "eks-pod-identity-token" then .name = "alternate-eks-token" else . end)
        | if (.spec | has("initContainers")) then
            .spec.initContainers[0].volumeMounts |= map(if .name == "eks-pod-identity-token" then .name = "alternate-eks-token" else . end)
          else . end
      ' <<<"${fixture}")
      ;;
    eks-dual-irsa-mount)
      fixture=$(jq -c '
        .spec.containers[0].volumeMounts += [{name:"eks-pod-identity-token",mountPath:"/var/run/secrets/eks.amazonaws.com/serviceaccount",readOnly:true}]
        | if (.spec | has("initContainers")) then
            .spec.initContainers[0].volumeMounts += [{name:"eks-pod-identity-token",mountPath:"/var/run/secrets/eks.amazonaws.com/serviceaccount",readOnly:true}]
          else . end
      ' <<<"${fixture}")
      ;;
    eks-second-mount)
      fixture=$(jq -c '
        .spec.containers[0].volumeMounts += [{name:"eks-pod-identity-token",mountPath:"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount-shadow",readOnly:true}]
      ' <<<"${fixture}")
      ;;
    eks-duplicate-full-uri)
      fixture=$(jq -c '.spec.containers[0].env += [{name:"AWS_CONTAINER_CREDENTIALS_FULL_URI",value:"http://127.0.0.1/credentials"}]' <<<"${fixture}")
      ;;
    eks-duplicate-token-file)
      fixture=$(jq -c '
        if (.spec | has("initContainers")) then
          .spec.initContainers[0].env += [{name:"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",value:"/tmp/shadow-token"}]
        else
          .spec.containers[0].env += [{name:"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",value:"/tmp/shadow-token"}]
        end
      ' <<<"${fixture}")
      ;;
    irsa-duplicate-role-env)
      fixture=$(jq -c '.spec.containers[0].env += [{name:"AWS_ROLE_ARN",value:"arn:aws:iam::123456789012:role/shadow"}]' <<<"${fixture}")
      ;;
    eks-token-without-label)
      fixture=$(jq -c 'del(.metadata.labels["eks.amazonaws.com/pod-identity"])' <<<"${fixture}")
      ;;
    eks-label-wrong-value)
      fixture=$(jq -c '.metadata.labels["eks.amazonaws.com/pod-identity"] = "disabled"' <<<"${fixture}")
      ;;
    identity-alternate-mount)
      fixture=$(jq -c '.spec.containers[0].volumeMounts[-1].mountPath = "/var/run/secrets/alternate"' <<<"${fixture}")
      ;;
    identity-init-subpath)
      fixture=$(jq -c '
        if (.spec | has("initContainers")) then
          .spec.initContainers[0].volumeMounts[-1].subPath = "token"
        else
          .spec.containers[0].volumeMounts[-1].subPath = "token"
        end
      ' <<<"${fixture}")
      ;;
  esac

  jq . <<<"${fixture}"
}

if [[ "${1:-}" == --admission-fixture ]]; then
  shift
  admission_fixture "$@"
  exit 0
fi

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

admission_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/wiremock-validatingadmissionpolicy.yaml)"
admission_binding_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --show-only templates/wiremock-validatingadmissionpolicybinding.yaml)"
persistent_admission_render="$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set fullnameOverride=custom-fleet \
  --set storage.persistent=true \
  --set storage.s3.bucket=task9-test \
  --show-only templates/wiremock-validatingadmissionpolicy.yaml)"

for fragment in \
  'apiVersion: admissionregistration.k8s.io/v1' \
  'kind: ValidatingAdmissionPolicy' \
  'name: unusual-release-testing-wiremock' \
  'failurePolicy: Fail' \
  'request.userInfo.username' \
  'system:serviceaccount:testing:custom-fleet-pod-manager' \
  'app.kubernetes.io/name' \
  'mock-fleet-wiremock' \
  'app.kubernetes.io/managed-by' \
  'mock-fleet/mock-id' \
  'eks.amazonaws.com/pod-identity' \
  'AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE' \
  'AWS_CONTAINER_CREDENTIALS_FULL_URI' \
  'http://169.254.170.23/v1/credentials' \
  "volume.name == 'eks-pod-identity-token'" \
  "serviceAccountToken.path == 'eks-pod-identity-token'" \
  'variables.wiremock.env.filter(other, other.name == env.name).size() == 1' \
  'custom-fleet-wiremock' \
  'wiremock/wiremock:3.13.2-2' \
  'automountServiceAccountToken' \
  "!key.startsWith('container.apparmor.security.beta.kubernetes.io/')" \
  'appArmorProfile.type' \
  "['RuntimeDefault', 'Localhost']" \
  'seLinuxOptions' \
  "['container_t', 'container_init_t', 'container_kvm_t', 'container_engine_t']" \
  'procMount' \
  'quantity(' \
  'sts.amazonaws.com' \
  'pods.eks.amazonaws.com'; do
  grep -Fq -- "${fragment}" <<<"${admission_render}" \
    || fail "WireMock admission policy is missing: ${fragment}"
done

grep -Fq '!has(object.spec.initContainers[0].restartPolicy)' <<<"${persistent_admission_render}" \
  || fail 'Persistent WireMock admission policy permits a native init sidecar restartPolicy'

for fragment in \
  'kind: ValidatingAdmissionPolicyBinding' \
  'name: unusual-release-testing-wiremock' \
  'policyName: unusual-release-testing-wiremock' \
  'validationActions:' \
  '- Deny' \
  'kubernetes.io/metadata.name: "testing"' \
  'operations:' \
  '- CREATE' \
  'resources:' \
  '- pods'; do
  grep -Fq -- "${fragment}" <<<"${admission_binding_render}" \
    || fail "WireMock admission policy binding is missing: ${fragment}"
done

disabled_admission="$(helm template unusual-release "${chart_dir}" --namespace testing \
  --set wiremock.admissionPolicy.enabled=false)"
if grep -Fq 'kind: ValidatingAdmissionPolicy' <<<"${disabled_admission}"; then
  fail "WireMock admission resources must render only when wiremock.admissionPolicy.enabled=true"
fi

for accepted_fixture in accepted accepted-workload-identity accepted-eks-pod-identity; do
  fixture=$(admission_fixture "${accepted_fixture}")
  jq -e '.kind == "Pod" and .spec.automountServiceAccountToken == false' >/dev/null <<<"${fixture}" \
    || fail "Accepted admission fixture is malformed: ${accepted_fixture}"
done
irsa_fixture=$(admission_fixture accepted-workload-identity)
jq -e '
  (.metadata.labels | length) == 3 and
  (.metadata.labels | has("eks.amazonaws.com/pod-identity") | not) and
  .spec.volumes[0].projected.sources[0].serviceAccountToken.audience == "sts.amazonaws.com"
' >/dev/null <<<"${irsa_fixture}" || fail 'IRSA fixture acquired the EKS Pod Identity label'
eks_fixture=$(admission_fixture accepted-eks-pod-identity)
jq -e '
  (.metadata.labels | length) == 4 and
  .metadata.labels["eks.amazonaws.com/pod-identity"] == "enabled" and
  .spec.volumes[0].name == "eks-pod-identity-token" and
  .spec.volumes[0].projected.sources[0].serviceAccountToken.audience == "pods.eks.amazonaws.com" and
  .spec.volumes[0].projected.sources[0].serviceAccountToken.path == "eks-pod-identity-token" and
  ([.spec.containers[0].volumeMounts[] | select(.name == "eks-pod-identity-token")] | length) == 1
' >/dev/null <<<"${eks_fixture}" || fail 'EKS Pod Identity fixture does not match the current upstream mutation'
for rejected_fixture in privileged hostpath wrong-image wrong-service-account label-spoofing missing-limits excessive-limits alternate-sidecar identity-alternate-mount identity-init-subpath pod-apparmor-unconfined container-apparmor-unconfined deprecated-apparmor-annotation pod-selinux-user container-selinux-type container-procmount-unmasked eks-label-without-token eks-token-without-label eks-label-wrong-value extra-unrelated-label eks-nonstandard-token-path eks-alternate-volume-name eks-dual-irsa-mount eks-second-mount eks-duplicate-full-uri eks-duplicate-token-file irsa-duplicate-role-env; do
  fixture=$(admission_fixture "${rejected_fixture}")
  jq -e '.kind == "Pod"' >/dev/null <<<"${fixture}" \
    || fail "Rejected admission fixture is malformed: ${rejected_fixture}"
done
for persistent_rejected_fixture in native-init-sidecar init-apparmor-unconfined init-selinux-role init-procmount-unmasked; do
  fixture=$(admission_fixture "${persistent_rejected_fixture}" testing custom-fleet custom-fleet-wiremock wiremock/wiremock:3.13.2-2 true custom-fleet-pvc /mock-fleet)
  jq -e '.kind == "Pod" and (.spec.initContainers | length) == 1' >/dev/null <<<"${fixture}" \
    || fail "Persistent rejected admission fixture is malformed: ${persistent_rejected_fixture}"
done

long_release=release-with-a-long-name-for-admission-policy-scope
long_name_a=$(helm template "${long_release}" "${chart_dir}" --namespace namespace-a-with-a-long-distinguishing-suffix \
  --show-only templates/wiremock-validatingadmissionpolicy.yaml \
  | awk '$1 == "name:" {print $2; exit}')
long_name_b=$(helm template "${long_release}" "${chart_dir}" --namespace namespace-b-with-a-long-distinguishing-suffix \
  --show-only templates/wiremock-validatingadmissionpolicy.yaml \
  | awk '$1 == "name:" {print $2; exit}')
[[ "${long_name_a}" != "${long_name_b}" && ${#long_name_a} -le 63 && ${#long_name_b} -le 63 ]] \
  || fail "Cluster-scoped admission policy names must remain unique for long release namespace scopes"

expect_render_failure 'admission without dedicated WireMock service account' \
  'wiremock.serviceAccount must select a dedicated service account when wiremock.admissionPolicy.enabled=true' \
  --set wiremock.serviceAccount.create=false --set wiremock.serviceAccount.name=

for shared_identity_case in \
  '--set serviceAccount.name=shared --set wiremock.serviceAccount.name=shared' \
  '--set fullnameOverride=custom-fleet --set serviceAccount.name=custom-fleet-wiremock' \
  '--set fullnameOverride=custom-fleet --set wiremock.serviceAccount.name=custom-fleet-pod-manager'; do
  read -r -a shared_identity_args <<<"${shared_identity_case}"
  expect_render_failure 'API and WireMock service accounts resolve to the same identity' \
    'API and WireMock service accounts must resolve to different names when wiremock.admissionPolicy.enabled=true' \
    "${shared_identity_args[@]}"
done

annotated_wiremock_service_account=$(helm template unusual-release "${chart_dir}" \
  --namespace testing \
  --set-string 'wiremock.serviceAccount.annotations.eks\.amazonaws\.com/role-arn=arn:aws:iam::123456789012:role/mock-fleet' \
  --show-only templates/wiremock-serviceaccount.yaml)
grep -Fq 'eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/mock-fleet' <<<"${annotated_wiremock_service_account}" \
  || fail 'Dedicated identity validation removed allowed WireMock service-account annotations'

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
expect_render_failure 'HTTP target collides with Hazelcast' '/fleet/api/service/ports/targetHttp' \
  --set fleet.api.service.ports.targetHttp=5701
expect_render_failure 'HTTP target drifts from the application listener' '/fleet/api/service/ports/targetHttp' \
  --set fleet.api.service.ports.targetHttp=9090
expect_render_failure 'Hazelcast collides with HTTP' 'hazelcast.port must not equal fleet.api.service.ports.targetHttp' \
  --set hazelcast.port=8080
expect_render_failure 'precision-sensitive CPU request below floor' 'wiremock.config.default.resources.requests.cpu must not be below wiremock.resourcePolicy.requestFloor.cpu' \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=9007199254740993 \
  --set-string wiremock.config.default.resources.requests.cpu=9007199254740992 \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=9007199254740994 \
  --set-string wiremock.config.default.resources.limits.cpu=9007199254740994
expect_render_failure 'milli request just below floor' 'wiremock.config.default.resources.requests.cpu must not be below wiremock.resourcePolicy.requestFloor.cpu' \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=1 \
  --set-string wiremock.config.default.resources.requests.cpu=999m
expect_render_failure 'milli limit just above ceiling' 'wiremock.config.default.resources.limits.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu' \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=1 \
  --set-string wiremock.config.default.resources.limits.cpu=1001m
expect_render_failure 'binary-equivalent request just below floor' 'wiremock.config.default.resources.requests.memory must not be below wiremock.resourcePolicy.requestFloor.memory' \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Gi \
  --set-string wiremock.config.default.resources.requests.memory=1073741823
expect_render_failure 'binary-equivalent limit just above ceiling' 'wiremock.config.default.resources.limits.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory' \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1Gi \
  --set-string wiremock.config.default.resources.limits.memory=1073741825
expect_render_failure 'unsupported uppercase decimal kilo suffix' '/wiremock/resourcePolicy/requestFloor/cpu' \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=1K
expect_render_failure 'Fabric8-inexact Ei resource quantity' '/wiremock/resourcePolicy/requestFloor/memory' \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Ei \
  --set-string wiremock.config.default.resources.requests.memory=1152921504606846976 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1Ei \
  --set-string wiremock.config.default.resources.limits.memory=1152921504606846976
expect_render_failure 'Fabric8-inexact Ei helper bypass' 'wiremock.resourcePolicy.requestFloor.memory does not support Ei because Fabric8 cannot compare it exactly' \
  --skip-schema-validation \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Ei \
  --set-string wiremock.config.default.resources.requests.memory=1152921504606846976 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1Ei \
  --set-string wiremock.config.default.resources.limits.memory=1152921504606846976
expect_render_failure 'Pi-equivalent request just below floor' 'wiremock.config.default.resources.requests.memory must not be below wiremock.resourcePolicy.requestFloor.memory' \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Pi \
  --set-string wiremock.config.default.resources.requests.memory=1125899906842623 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1Pi \
  --set-string wiremock.config.default.resources.limits.memory=1125899906842624
expect_render_failure 'Pi-equivalent limit just above ceiling' 'wiremock.config.default.resources.limits.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory' \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Pi \
  --set-string wiremock.config.default.resources.requests.memory=1125899906842624 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1Pi \
  --set-string wiremock.config.default.resources.limits.memory=1125899906842625

helm template exact-equivalence "${chart_dir}" \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=1e-3 \
  --set-string wiremock.config.default.resources.requests.cpu=1m \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=1000u \
  --set-string wiremock.config.default.resources.limits.cpu=1000000n \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Gi \
  --set-string wiremock.config.default.resources.requests.memory=1073741824 \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1024Mi \
  --set-string wiremock.config.default.resources.limits.memory=1048576Ki \
  --show-only templates/api-deployment.yaml >/dev/null

helm template decimal-equivalence "${chart_dir}" \
  --set-string wiremock.resourcePolicy.requestFloor.cpu=1e3 \
  --set-string wiremock.config.default.resources.requests.cpu=1k \
  --set-string wiremock.resourcePolicy.limitCeiling.cpu=1000000m \
  --set-string wiremock.config.default.resources.limits.cpu=1000 \
  --show-only templates/api-deployment.yaml >/dev/null

helm template pi-equivalence "${chart_dir}" \
  --set-string wiremock.resourcePolicy.requestFloor.memory=1Pi \
  --set-string wiremock.config.default.resources.requests.memory=1024Ti \
  --set-string wiremock.resourcePolicy.limitCeiling.memory=1048576Gi \
  --set-string wiremock.config.default.resources.limits.memory=1125899906842624 \
  --show-only templates/api-deployment.yaml >/dev/null

for invariant in \
  'kind: ResourceQuota' \
  'name: MOCK_FLEET_MAX_ACTIVE_MOCKS' \
  'name: MOCK_FLEET_MAPPINGS_MAX_DEPTH' \
  'name: MOCK_FLEET_WIREMOCK_RESOURCE_LIMIT_CEILING_MEMORY'; do
  grep -Fq "${invariant}" <<<"${default_render}" || fail "Secure default render is missing: ${invariant}"
done

echo "Helm security render contract passed"
