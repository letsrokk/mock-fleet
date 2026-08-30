# mock-fleet Helm Chart

This chart deploys `mock-fleet` as three core Kubernetes services and one optional service:

- `fleet-proxy`: routes incoming HTTP requests to per-mock WireMock pods.
- `fleet-api`: manages mock pods, WireMock config, lifecycle cleanup, Hazelcast state, and persisted mappings.
- `fleet-dash`: serves the dashboard under `/__fleet/`.
- `fleet-mcp`: exposes typed MCP tools under `/__fleet/mcp` when enabled.

## Install

Install from GHCR:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace
```

Install from this repository:

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace
```

## Cluster Security Prerequisites

The chart renders `NetworkPolicy` objects, but Kubernetes accepts them even when the installed network plugin does not enforce them. A NetworkPolicy-capable CNI, such as Calico or Cilium, is a deployment prerequisite. Installing and configuring that CNI, and proving that it enforces the rendered selectors, belongs to the cluster operator. `make local-deploy` neither checks nor changes CNI capability. The bridge CNI in the Minikube cluster reviewed for this release accepted the policies but did not enforce them.

The chart installs `admissionregistration.k8s.io/v1` `ValidatingAdmissionPolicy` and `ValidatingAdmissionPolicyBinding` objects by default. Use Kubernetes 1.30 or newer, where [Validating Admission Policy is stable](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/). The current policy, including both persistent and non-persistent pod shapes, was server-side compiled and exercised on Kubernetes 1.36.4.

The local deployment helper labels its target namespace with `restricted` Pod Security Admission enforce, audit, and warn labels pinned to the API server's current major and minor version. For other deployment workflows, the cluster operator must apply an equivalent namespace policy. Mock Fleet workloads need writable runtime paths, so the hardened manifests do not require a read-only root filesystem.

### Verify NetworkPolicy enforcement

Run this two-pod test after installation. The first pod is an otherwise equivalent unselected positive control. It must reach the cluster-internal Kubernetes service on TCP 443. If it cannot, the result is inconclusive: fix baseline pod-to-service networking before evaluating NetworkPolicy. The second pod has exactly the two labels selected by the managed-WireMock egress policy. Only a successful control followed by a denied selected connection proves enforcement.

The commands use explicit exit states. `mock-fleet-networkpolicy-control` exits 0 only when baseline connectivity works and exits 2 when the test is inconclusive. `mock-fleet-networkpolicy-selected` exits 0 only when policy denies the connection and exits 1 when the internal connection succeeds. `set -e` stops the procedure before the selected test if the control is inconclusive.

```bash
set -euo pipefail
NAMESPACE=mock-fleet
KUBE_API_IP="$(kubectl -n default get service kubernetes -o jsonpath='{.spec.clusterIP}')"

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mock-fleet-networkpolicy-control
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: busybox:1.36
      command:
        - sh
        - -ec
        - |
          if nc -z -w 5 ${KUBE_API_IP} 443; then
            echo "CONTROL PASS: baseline cluster-internal TCP connection succeeded"
            exit 0
          fi
          echo "INCONCLUSIVE: baseline cluster-internal TCP connection failed"
          exit 2
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          cpu: 50m
          memory: 32Mi
EOF

kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.containerStatuses[0].state.terminated}' pod/mock-fleet-networkpolicy-control --timeout=60s
kubectl -n "$NAMESPACE" logs pod/mock-fleet-networkpolicy-control
test "$(kubectl -n "$NAMESPACE" get pod mock-fleet-networkpolicy-control -o jsonpath='{.status.phase}')" = Succeeded

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mock-fleet-networkpolicy-selected
  labels:
    app.kubernetes.io/name: mock-fleet-wiremock
    app.kubernetes.io/managed-by: mock-fleet
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: busybox:1.36
      command:
        - sh
        - -ec
        - |
          if nc -z -w 5 ${KUBE_API_IP} 443; then
            echo "POLICY FAIL: selected cluster-internal TCP connection succeeded"
            exit 1
          fi
          echo "POLICY PASS: selected cluster-internal TCP connection was denied"
          exit 0
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          cpu: 50m
          memory: 32Mi
EOF

kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.containerStatuses[0].state.terminated}' pod/mock-fleet-networkpolicy-selected --timeout=60s
kubectl -n "$NAMESPACE" logs pod/mock-fleet-networkpolicy-selected
test "$(kubectl -n "$NAMESPACE" get pod mock-fleet-networkpolicy-selected -o jsonpath='{.status.phase}')" = Succeeded
```

Remove both pods after a pass, failure, or inconclusive result:

```bash
kubectl -n "$NAMESPACE" delete pod \
  mock-fleet-networkpolicy-control \
  mock-fleet-networkpolicy-selected \
  --ignore-not-found
```

## Routing And Ingress

Ingress is disabled by default. When enabled, the chart routes:

- `/__fleet/api/*` to `fleet-api`
- `/__fleet/mcp` to `fleet-mcp` when `fleet.mcp.enabled=true`
- `/__fleet/proxy/health/*` to `fleet-proxy`
- `/__fleet/dash/health/*` to `fleet-dash`
- `/__fleet/*` to `fleet-dash`
- `/` to `fleet-proxy`
- `*.ingress.host` to `fleet-proxy` when `fleet.proxy.routing.mode=HOST`

Example:

```bash
helm upgrade --install mock-fleet oci://ghcr.io/letsrokk/charts/mock-fleet \
  --version <version> \
  --namespace mock-fleet \
  --create-namespace \
  --set ingress.enabled=true \
  --set ingress.host=mock-fleet.example.com
```

`fleet-mcp` reaches the Fleet API and Fleet Proxy through helper-derived ClusterIP DNS names. It does not call the mock-pod resolver. In `PATH` mode it sends `/<mockId>/__admin/...` to Fleet Proxy. In `HOST` mode it connects to the same Proxy ClusterIP and sets `Host: <mockId>.<fleetHost>`.

Fleet Proxy continues to expose direct WireMock `/__admin` requests on ordinary mock URLs without authentication. MCP uses the same external access boundary as the Fleet API and does not change that behavior.

## Lifecycle and API contracts

`fleet-api` initializes its editable ConfigMap before it becomes ready, so either API replica can serve a fresh configuration view. Config rows contain `lifecycle` (`STOPPED`, `STARTING`, `RUNNING`, or `FAILED`) rather than a boolean active flag. Config PUT and DELETE responses are `{config,apply:{mockId,mode,lifecycle}}`. The `restartActive` mode restarts only a STARTING or RUNNING mock and normally returns STARTING while replacement continues.

The chart renders a named WireMock version-catalog ConfigMap. `wiremock.containerImage` supplies its exact initial default image and repository, while `wiremock.supportedImageTags` supplies the selectable version inventory. `GET /__fleet/api/config` exposes `defaultVersion`, `versions` as `{version,image,selectable}`, and `catalogResourceVersion`. A `selectable: false` entry is retained only to keep an existing baseline or user pin runnable; it cannot be selected for a different mock. A mock with no explicit version inherits the current catalog default. A mock with an exact pin keeps that desired version when the default advances.

Each configuration row exposes the baseline, user, and effective `version`, plus top-level desired `wireMockVersion` and nullable active `runtimeVersion`. `futureOnly` saves the change without replacing an active pod, so desired and runtime versions can differ until the next start. `restartActive` replaces only a STARTING or RUNNING pod; it does not start a STOPPED or FAILED mock. A version absent from the selectable catalog, except the same mock's existing retained pin, returns `UNSUPPORTED_WIREMOCK_VERSION`. A known option outside the desired version's compatibility range returns `UNSUPPORTED_WIREMOCK_OPTION`. Both failures happen before the user ConfigMap changes.

`POST /__fleet/api/mocks/{mockId}/start` is idempotent. It returns 200 for RUNNING or 202 for STARTING with `retryAfterMs: 1000`. Poll the same endpoint until RUNNING. DELETE is also idempotent. It waits for an existing pod to be removed before returning STOPPED and returns STOPPED directly for already stopped or absent mocks. Lifecycle and config failures use `ApiError {code,message,retryable,stateMayHaveChanged,details}`. Optimistic config conflicts use `CONFIG_CONFLICT` and include `expectedVersion` and `currentVersion`.

The full REST schema is checked in at `fleet-api/src/main/resources/META-INF/openapi.yaml` and is served by the running API at `/__fleet/api/openapi?format=json`.

## Upgrade And Security Notes

- Runtime dependencies now use Quarkus 3.33.3.1 and Hazelcast 5.7.0. Fleet Proxy also rejects absolute, scheme-relative, fragmented, malformed-percent, and backslash-bearing request targets before resolution or outbound I/O, and it removes inbound authority, framing, and hop-by-hop headers before forwarding. These changes do not add API, Admin-route, or MCP authentication.
- The plaintext WireMock options `--ca-keystore-password`, `--keystore-password`, `--key-manager-password`, and `--truststore-password` are unsupported in both `--name=value` and split-argument forms. New writes are rejected before persistence. Existing values are redacted from API output and fail closed when a mock starts. Before upgrading, remove these entries from `wiremock.default.options` and every `wiremock.mocks[].options` list in `<release-fullname>-wiremock-user-config`, and from `wiremock.config.default.options` or `wiremock.config.mocks[].options` in Helm values. Password-protected keystores are unsupported until a Secret-backed path exists. Treat the retained ConfigMap as sensitive until cleanup is complete.
- The editable `<release-fullname>-wiremock-user-config` ConfigMap is now a retained chart resource. A connected Helm install creates it. A connected upgrade leaves an existing API-created object untouched through a `lookup` guard, so Helm does not try to adopt or overwrite saved configuration. Offline `helm template` cannot perform that lookup and renders the ConfigMap; an offline render/apply workflow must use an apply tool that safely reconciles the existing object. The API now requires the object and returns HTTP 503 `CONFIG_UNAVAILABLE` if it is absent; it no longer creates it.
- `wiremock.containerImage` remains the upgrade-compatible setting for the initial default, but it no longer defines one deployment-wide runtime image. Its semantic version must occur exactly once in `wiremock.supportedImageTags`; the exact image revision from `containerImage` wins for that default version. If an existing release overrides `wiremock.containerImage`, add one tag with the same semantic version to `wiremock.supportedImageTags` before upgrading. The supported tag's image revision can differ because `containerImage` remains authoritative for the default entry. The other tags use the repository portion of `containerImage`. Digests, floating tags, prereleases, Alpine variants, non-3.x versions, duplicate semantic versions, and an inventory that omits the default are rejected at render time.
- Managed resources accept only `cpu` and `memory`. Missing or partial keys inherit the chart baseline; an empty override cannot erase it. Effective requests must meet `wiremock.resourcePolicy.requestFloor`, limits must not exceed `wiremock.resourcePolicy.limitCeiling`, and each request must not exceed its limit. Numeric-looking quantities such as `4` or `0.5` must be quoted so YAML supplies strings to chart validation; unit-bearing values such as `128Mi` are already strings and need no quotes. DecimalSI, `Ki` through `Pi`, and bounded exponent forms are supported; `Ei` is rejected because its comparison differs from the Kubernetes client used by the API.
- Numeric WireMock workload-shaping options now accept only catalog-advertised integers and bounds. Existing fractional, negative, or oversized values fail validation and must be corrected before a mock can start.
- Mock starts reserve cluster-wide capacity across replicas. `fleet.api.maxActiveMocks` is the cluster-wide maximum for distinct reserved, starting, or running mocks; exceeding it returns HTTP 429 `MOCK_CAPACITY_EXHAUSTED`. Each API replica has its own executor with `fleet.api.maxConcurrentStarts` workers and `fleet.api.queuedStartCapacity` waiting positions. Aggregate executor capacity is each value multiplied by the API replica count, while the cluster-wide active limit still applies. The default Minikube deployment has two API replicas, so it provides up to 8 start workers and 32 queue positions, subject to the 20-mock cluster-wide limit. A full replica-local queue returns HTTP 503 `MOCK_START_QUEUE_FULL`. Explicit successful starts initialize their idle-cleanup timestamp.
- Mapping tree reads and recursive folder deletion apply inclusive `fleet.api.mappings.maxDepth` and `maxEntries` budgets. Overflow returns HTTP 400 `MAPPINGS_TRAVERSAL_LIMIT`; storage or unsupported secure-traversal behavior returns retryable HTTP 503 `MAPPINGS_STORAGE_ERROR`. Recursive deletion discovers and validates the bounded set before deleting, so a traversal-limit failure deletes nothing.
- Managed-pod deletion performs a fresh Kubernetes GET, checks the two stable ownership labels plus the expected `mock-fleet/mock-id`, and deletes with the fetched UID as a precondition. Missing pods remain idempotent; a wrong label, missing UID, or same-name replacement fails closed. The API Role has no Deployment authority and can mutate only the named retained user ConfigMap.
- `resourceQuota.enabled=true` supplies a second boundary if application admission fails. Size pod, CPU, and memory quota for `fleet.api.maxActiveMocks`, all API replicas, the aggregate per-replica start burst, every enabled fixed workload, rollout surge, and expected operational or probe pods. Quota may intentionally cap the usable mock count below `maxActiveMocks`; undersizing it can also block starts, upgrades, and probes.
- `make local-deploy` applies `restricted` PSA enforce, audit, and warn labels at the current server minor. Before upgrading a shared namespace, verify that every non-Mock-Fleet workload also satisfies that profile; PSA can reject unrelated noncompliant pods. A direct Helm install does not manage namespace labels, so its operator must apply and maintain the equivalent policy.
- The admission policy requires the dedicated WireMock service account, exact generated pod shape, restricted security context, an image present under `selectable.*` or `retained.*` in the named version-catalog ConfigMap, the resource envelope, and one supported workload-identity mode. Its binding uses that exact ConfigMap as a parameter, `failurePolicy: Fail`, and `parameterNotFoundAction: Deny`; a missing or unavailable parameter denies managed-pod creation. `wiremock.admissionPolicy.enabled=false` removes the Kubernetes boundary that constrains a compromised API service account. Kubernetes 1.30 or newer is required while it is enabled; compatibility was verified on 1.36.4.
- `fleet.api.networkPolicy.enabled=false` removes API/Hazelcast ingress isolation. `fleet.mcp.outbound.networkPolicy.enabled=false` removes managed-WireMock private/cluster-network egress isolation even when MCP itself is disabled. `resourceQuota.enabled=false` removes the namespace resource backstop. Disable any of these only when another cluster control provides the same boundary.

Edit the retained configuration in place before the first post-upgrade start when it contains a prohibited password option. Replace `<release-fullname>` with the chart's resolved full name; do not copy the old plaintext values into issue trackers or command history.

```bash
NAMESPACE=mock-fleet
kubectl -n "$NAMESPACE" edit configmap <release-fullname>-wiremock-user-config
```

## Persistent Mappings

Persistent mappings are disabled by default. To enable them, set `storage.persistent=true`, use `storage.type=s3`, and provide `storage.s3.bucket`.

The chart creates a static S3 CSI PV/PVC and mounts it into:

- spawned WireMock pods at `/home/wiremock`
- `fleet-api` at `storage.mappingsPath`

`storage.s3.authenticationSource=pod` asks the S3 CSI driver to use the mounting pod's identity. API replicas use the API service account and its `serviceAccount.annotations`; managed WireMock pods use the separate service account selected by `wiremock.serviceAccount.name` and its `wiremock.serviceAccount.annotations`. IRSA or EKS Pod Identity admission can inject one audience-bound projected token plus its read-only mount and AWS credential environment variables. This token is separate from the general Kubernetes API token: managed WireMock pods keep `automountServiceAccountToken=false`.

The admission policy accepts zero identity projections or exactly one projection with an audience in `wiremock.admissionPolicy.workloadIdentity.allowedTokenAudiences` and an expiry from 600 through 86400 seconds. A configured non-EKS audience must use the standard read-only `/var/run/secrets/eks.amazonaws.com/serviceaccount` mount plus one nonempty `AWS_ROLE_ARN` and one matching `AWS_WEB_IDENTITY_TOKEN_FILE`. The reserved `pods.eks.amazonaws.com` mode requires the `eks.amazonaws.com/pod-identity=enabled` label, volume and token path `eks-pod-identity-token`, the read-only `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount` mount, and the standard `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` and `AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials` values. Mixed modes, extra identity volumes or mounts, duplicate credential variables, unconfigured audiences, and credential variables without their token are denied. The application container and the persistent mappings initializer must independently have the selected identity shape.

`storage.s3.mountOptions` includes `allow-delete`, `allow-overwrite`, and `metadata-ttl minimal` by default. The first two options support dashboard and MCP file mutations. The minimal metadata TTL reduces cross-mount staleness while the CSI data cache is enabled; Mountpoint does not coordinate concurrent writes to the same key, so clients must not race those writes.

For a full Minikube/SeaweedFS verification, use `bin/cluster-e2e.sh`. The live suite requires a prepared SeaweedFS-compatible S3 CSI driver and explicit S3 credentials. It creates a unique namespace and bucket, validates two API replicas and the REST/MCP recovery paths, and removes all owned resources through an EXIT trap. `--dry-run` prints the resolved scope, `--self-test` performs static harness checks, and `--keep` retains failed-run resources for investigation. The `Cluster E2E` GitHub Actions workflow runs only through `workflow_dispatch` on a prepared runner; normal push checks stay unchanged.

## Values

### Global

| Value | Default | Description |
| --- | --- | --- |
| `nameOverride` | `""` | Override the chart name used in resource names. |
| `fullnameOverride` | `""` | Override the full release resource name. |
| `namespaceOverride` | `""` | Override the namespace rendered into namespaced resources. |
| `clusterDomain` | `cluster.local` | Kubernetes cluster DNS suffix used for internal API and Proxy service URLs. |

### Proxy

| Value | Default | Description |
| --- | --- | --- |
| `fleet.proxy.dev.enabled` | `false` | Run proxy in Quarkus dev mode and force one replica. |
| `fleet.proxy.dev.quarkusLaunchDevmode` | `"true"` | Value for `QUARKUS_LAUNCH_DEVMODE` when proxy dev mode is enabled. |
| `fleet.proxy.dev.javaToolOptions` | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` | Proxy debug JVM options used in dev mode. |
| `fleet.proxy.image.repository` | `ghcr.io/letsrokk/mock-fleet/proxy` | Proxy image repository. |
| `fleet.proxy.image.tag` | `""` | Proxy image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.proxy.image.pullPolicy` | `IfNotPresent` | Proxy image pull policy. |
| `fleet.proxy.routing.mode` | `HOST` | Mock routing mode: `HOST` or `PATH`. |
| `fleet.proxy.logging.json` | `false` | Enable JSON console logging for the proxy. |
| `fleet.proxy.logging.level` | `INFO` | Proxy `com.github.letsrokk` log level; use `TRACE` to log proxied requests. |
| `fleet.proxy.replicas` | `2` | Proxy replica count when dev mode is disabled. |
| `fleet.proxy.service.type` | `ClusterIP` | Proxy service type. |
| `fleet.proxy.service.ports.http` | `80` | Proxy service HTTP port. |
| `fleet.proxy.service.ports.targetHttp` | `8080` | Proxy container HTTP port. |
| `fleet.proxy.service.ports.debug` | `5005` | Proxy service debug port. |
| `fleet.proxy.service.ports.targetDebug` | `5005` | Proxy container debug port. |
| `fleet.proxy.probes.liveness.path` | `/__fleet/proxy/health/live` | Proxy liveness probe path. |
| `fleet.proxy.probes.liveness.initialDelaySeconds` | `15` | Proxy liveness probe initial delay. |
| `fleet.proxy.probes.liveness.periodSeconds` | `20` | Proxy liveness probe period. |
| `fleet.proxy.probes.liveness.timeoutSeconds` | `5` | Proxy liveness probe timeout. |
| `fleet.proxy.probes.liveness.successThreshold` | `1` | Proxy liveness probe success threshold. |
| `fleet.proxy.probes.liveness.failureThreshold` | `3` | Proxy liveness probe failure threshold. |
| `fleet.proxy.probes.readiness.path` | `/__fleet/proxy/health/ready` | Proxy readiness probe path. |
| `fleet.proxy.probes.readiness.initialDelaySeconds` | `5` | Proxy readiness probe initial delay. |
| `fleet.proxy.probes.readiness.periodSeconds` | `10` | Proxy readiness probe period. |
| `fleet.proxy.probes.readiness.timeoutSeconds` | `5` | Proxy readiness probe timeout. |
| `fleet.proxy.probes.readiness.successThreshold` | `1` | Proxy readiness probe success threshold. |
| `fleet.proxy.probes.readiness.failureThreshold` | `3` | Proxy readiness probe failure threshold. |
| `fleet.proxy.probes.startup.path` | `/__fleet/proxy/health/started` | Proxy startup probe path. |
| `fleet.proxy.probes.startup.initialDelaySeconds` | `5` | Proxy startup probe initial delay. |
| `fleet.proxy.probes.startup.periodSeconds` | `10` | Proxy startup probe period. |
| `fleet.proxy.probes.startup.timeoutSeconds` | `10` | Proxy startup probe timeout. |
| `fleet.proxy.probes.startup.successThreshold` | `1` | Proxy startup probe success threshold. |
| `fleet.proxy.probes.startup.failureThreshold` | `3` | Proxy startup probe failure threshold. |
| `fleet.proxy.resources.requests.cpu` | `"0.25"` | Proxy CPU request. |
| `fleet.proxy.resources.requests.memory` | `256Mi` | Proxy memory request. |
| `fleet.proxy.resources.limits.cpu` | `"1"` | Proxy CPU limit. |
| `fleet.proxy.resources.limits.memory` | `1Gi` | Proxy memory limit. |

### API

| Value | Default | Description |
| --- | --- | --- |
| `fleet.api.dev.enabled` | `false` | Run API in Quarkus dev mode and force one replica. |
| `fleet.api.dev.quarkusLaunchDevmode` | `"true"` | Value for `QUARKUS_LAUNCH_DEVMODE` when API dev mode is enabled. |
| `fleet.api.dev.javaToolOptions` | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` | API debug JVM options used in dev mode. |
| `fleet.api.image.repository` | `ghcr.io/letsrokk/mock-fleet/api` | API image repository. |
| `fleet.api.image.tag` | `""` | API image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.api.image.pullPolicy` | `IfNotPresent` | API image pull policy. |
| `fleet.api.podInactivityThreshold` | `1M` | Time before inactive mock pods are eligible for cleanup. |
| `fleet.api.podCreationTimeout` | `1M` | Time to wait for a spawned WireMock pod to become ready. |
| `fleet.api.maxActiveMocks` | `20` | Maximum distinct mocks that may be reserved, starting, or running across API replicas. Exhaustion returns HTTP 429 `MOCK_CAPACITY_EXHAUSTED`. |
| `fleet.api.maxConcurrentStarts` | `4` | Pod-start workers per API replica; must not exceed cluster-wide `maxActiveMocks`. Aggregate workers equal this value times the API replica count. |
| `fleet.api.queuedStartCapacity` | `16` | Waiting start positions per API replica. Aggregate positions equal this value times the API replica count; replica-local saturation returns HTTP 503 `MOCK_START_QUEUE_FULL`. |
| `fleet.api.mappings.maxDepth` | `32` | Inclusive maximum relative depth for mapping tree reads and recursive deletion. |
| `fleet.api.mappings.maxEntries` | `10000` | Inclusive maximum examined entries, including the selected mock root, for one mapping traversal. |
| `fleet.api.networkPolicy.enabled` | `true` | Render API ingress isolation. Disabling it exposes HTTP and Hazelcast to any connectivity the cluster otherwise permits. |
| `fleet.api.logging.json` | `false` | Enable JSON console logging for the API. |
| `fleet.api.logging.level` | `INFO` | API `com.github.letsrokk` log level. |
| `fleet.api.replicas` | `2` | API replica count when dev mode is disabled; must be at least two for embedded Hazelcast redundancy. |
| `fleet.api.terminationGracePeriodSeconds` | `30` | Time allowed for graceful Hazelcast member shutdown and partition migration. |
| `fleet.api.updateStrategy.type` | `Recreate` | API deployment update strategy. The default stops the embedded Hazelcast cluster before starting a new version because Hazelcast Community Edition does not support mixed-version rolling member upgrades. |
| `fleet.api.updateStrategy.rollingUpdate.maxUnavailable` | `1` | Maximum unavailable API pods when `updateStrategy.type=RollingUpdate`; ignored by the default `Recreate` strategy. |
| `fleet.api.updateStrategy.rollingUpdate.maxSurge` | `1` | Maximum additional API pods when `updateStrategy.type=RollingUpdate`; ignored by the default `Recreate` strategy. |
| `fleet.api.pdb.enabled` | `true` | Create a PodDisruptionBudget for API/Hazelcast members. |
| `fleet.api.pdb.minAvailable` | `1` | Minimum API pods available during voluntary disruptions. |
| `fleet.api.service.type` | `ClusterIP` | API service type. |
| `fleet.api.service.ports.http` | `80` | API service HTTP port. |
| `fleet.api.service.ports.targetHttp` | `8080` | API container HTTP port. |
| `fleet.api.service.ports.debug` | `5006` | API service debug port. |
| `fleet.api.service.ports.targetDebug` | `5005` | API container debug port. |
| `fleet.api.probes.liveness.path` | `/__fleet/api/health/live` | API liveness probe path. |
| `fleet.api.probes.liveness.initialDelaySeconds` | `15` | API liveness probe initial delay. |
| `fleet.api.probes.liveness.periodSeconds` | `20` | API liveness probe period. |
| `fleet.api.probes.liveness.timeoutSeconds` | `5` | API liveness probe timeout. |
| `fleet.api.probes.liveness.successThreshold` | `1` | API liveness probe success threshold. |
| `fleet.api.probes.liveness.failureThreshold` | `3` | API liveness probe failure threshold. |
| `fleet.api.probes.readiness.path` | `/__fleet/api/health/ready` | API readiness probe path. |
| `fleet.api.probes.readiness.initialDelaySeconds` | `5` | API readiness probe initial delay. |
| `fleet.api.probes.readiness.periodSeconds` | `10` | API readiness probe period. |
| `fleet.api.probes.readiness.timeoutSeconds` | `5` | API readiness probe timeout. |
| `fleet.api.probes.readiness.successThreshold` | `1` | API readiness probe success threshold. |
| `fleet.api.probes.readiness.failureThreshold` | `3` | API readiness probe failure threshold. |
| `fleet.api.probes.startup.path` | `/__fleet/api/health/started` | API startup probe path. |
| `fleet.api.probes.startup.initialDelaySeconds` | `5` | API startup probe initial delay. |
| `fleet.api.probes.startup.periodSeconds` | `10` | API startup probe period. |
| `fleet.api.probes.startup.timeoutSeconds` | `10` | API startup probe timeout. |
| `fleet.api.probes.startup.successThreshold` | `1` | API startup probe success threshold. |
| `fleet.api.probes.startup.failureThreshold` | `30` | API startup probe failure threshold. |
| `fleet.api.resources.requests.cpu` | `"0.5"` | API CPU request. |
| `fleet.api.resources.requests.memory` | `512Mi` | API memory request. |
| `fleet.api.resources.limits.cpu` | `"2"` | API CPU limit. |
| `fleet.api.resources.limits.memory` | `2Gi` | API memory limit. |

### MCP

MCP is disabled by default. It uses Streamable HTTP and must run with one replica. MCP verifies each target mock runtime with `/__admin/version`. WireMock 3.0.x does not expose that endpoint, so MCP verifies the legacy Admin mapping response and resolves that mock's runtime version from Fleet API. If neither source reports a runtime version, version-gated tools fail closed.

| Value | Default | Description |
| --- | --- | --- |
| `fleet.mcp.enabled` | `false` | Deploy the MCP service and add its ingress route. |
| `fleet.mcp.image.repository` | `ghcr.io/letsrokk/mock-fleet/mcp` | MCP image repository. |
| `fleet.mcp.image.tag` | `""` | MCP image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.mcp.image.pullPolicy` | `IfNotPresent` | MCP image pull policy. |
| `fleet.mcp.replicas` | `1` | MCP replica count. The stable transport requires exactly one. |
| `fleet.mcp.apiBaseUrl` | `""` | Fleet API base URL override. Empty renders the API ClusterIP FQDN. |
| `fleet.mcp.proxyBaseUrl` | `""` | Fleet Proxy base URL override. Empty renders the Proxy ClusterIP FQDN. |
| `fleet.mcp.routing.mode` | `""` | `PATH` or `HOST`; empty inherits `fleet.proxy.routing.mode`. |
| `fleet.mcp.routing.fleetHost` | `""` | Host suffix used to select a mock in `HOST` mode; empty inherits `ingress.host`. |
| `fleet.mcp.allowedOrigins` | `[]` | Browser origins allowed to initialize MCP sessions; empty derives the ingress origin. |
| `fleet.mcp.outbound.exceptions` | `[]` | Explicit target hosts allowed through recorder, proxy, and webhook target checks. |
| `fleet.mcp.outbound.allowedListeners` | `[]` | WireMock serve-event listener names allowed in mappings, such as `webhook`. |
| `fleet.mcp.outbound.networkPolicy.enabled` | `true` | Render managed-WireMock egress isolation independently of `fleet.mcp.enabled`. A NetworkPolicy-capable CNI must enforce it. |
| `fleet.mcp.outbound.networkPolicy.dnsNamespace` | `kube-system` | Namespace containing the cluster DNS pods allowed by the WireMock egress policy. |
| `fleet.mcp.outbound.networkPolicy.dnsPodSelector` | `{k8s-app: kube-dns}` | Labels selecting the cluster DNS pods allowed by the WireMock egress policy. |
| `fleet.mcp.outbound.networkPolicy.allowedCidrs` | `[]` | Private or special-use CIDRs explicitly allowed at connection time. Configure the corresponding host in `outbound.exceptions` too. |
| `fleet.mcp.sensitiveHeaders` | common credential headers | Headers redacted from traffic, journal, and recorder results. |
| `fleet.mcp.timeout` | `10S` | Normal internal Fleet API and Proxy request timeout. |
| `fleet.mcp.lifecycleTimeout` | `70S` | Timeout for pod deletion through `stop_mock`; keep this longer than `fleet.api.podCreationTimeout`. |
| `fleet.mcp.defaultPageSize` | `50` | Default collection page size. |
| `fleet.mcp.maxPageSize` | `200` | Maximum collection page size. |
| `fleet.mcp.maxPayloadBytes` | `1048576` | Maximum complete structured result size. |
| `fleet.mcp.includedBodyBytes` | `262144` | Maximum body content included in a result. |
| `fleet.mcp.dependencyHealthTimeout` | `1S` | Timeout for each Fleet API and Fleet Proxy readiness check. |
| `fleet.mcp.maxCollectionScanBytes` | `67108864` | Maximum upstream bytes scanned while producing one collection page or checking references and recovery state. |
| `fleet.mcp.maxCollectionScanItems` | `100000` | Maximum upstream items scanned while producing one collection page or checking references and recovery state. |
| `fleet.mcp.service.type` | `ClusterIP` | MCP service type. |
| `fleet.mcp.service.ports.http` | `80` | MCP service HTTP port. |
| `fleet.mcp.service.ports.targetHttp` | `8080` | MCP container HTTP port. |

The MCP pod does not receive Kubernetes RBAC or a mounted service-account token. Only HTTP and HTTPS targets are accepted. MCP validates target hostnames before configuration, and the enabled-by-default WireMock egress `NetworkPolicy` renders independently of MCP. A capable CNI must enforce that policy when WireMock connects. Private, loopback, link-local, multicast, metadata, and special-use destinations remain blocked unless both the host is listed in `outbound.exceptions` and its address range is listed in `outbound.networkPolicy.allowedCidrs`. Configure the DNS namespace and pod selector for clusters that do not label CoreDNS as `k8s-app=kube-dns`; disable this policy only when another connection-time egress control provides the same boundary.

MCP publishes 32 tools, including `start_mock` and `get_recording_status`; `recording_status` is not an alias. Every WireMock Admin and traffic tool starts or checks the mock first. STARTING becomes a structured, retryable `MOCK_STARTING` result without proxying Admin traffic. Tool successes use strict tool-specific wrappers, and every failure uses `{error:{code,message,retryable,stateMayHaveChanged,details}}` with `isError: true`. Byte-bearing inputs and results use `{body:{encoding:utf8|base64,data,sizeBytes}}`. Recording stop/snapshot returns candidate IDs, count, and explicit match status. See `docs/mcp-contract.md` for examples and recovery behavior.

### WireMock

| Value | Default | Description |
| --- | --- | --- |
| `wiremock.podNamePrefix` | `mock-fleet` | Base prefix used for spawned WireMock pod names. |
| `wiremock.containerName` | `wiremock` | Container name used for spawned WireMock pods. |
| `wiremock.containerImage` | `wiremock/wiremock:3.13.2-2` | Exact initial default image and repository compatibility setting. Its semantic version must occur in `supportedImageTags`. |
| `wiremock.supportedImageTags` | `[3.13.2-2, 3.12.1-2, 3.11.0-1, 3.10.0-1, 3.9.2-1]` | Initial selectable exact tags. Each semantic version must be unique, and the `containerImage` version must occur exactly once. |
| `wiremock.containerImagePullPolicy` | `IfNotPresent` | Image pull policy for spawned WireMock pods. |
| `wiremock.terminationGracePeriodSeconds` | `5` | Graceful shutdown window for spawned WireMock pods. Fleet waits for full pod removal before replacement. |
| `wiremock.serviceAccount.create` | `true` | Create a dedicated service account for managed WireMock pods. |
| `wiremock.serviceAccount.name` | `""` | Service account name. A generated name is used when creation is enabled and this is empty. |
| `wiremock.serviceAccount.annotations` | `{}` | Dedicated WireMock service-account annotations for IRSA, EKS Pod Identity, or another workload-identity integration. |
| `wiremock.admissionPolicy.enabled` | `true` | Install fail-closed admission policy and binding for API-created managed pods. Requires Kubernetes 1.30 or newer; disabling it removes pod-shape enforcement. |
| `wiremock.admissionPolicy.workloadIdentity.allowedTokenAudiences` | `[sts.amazonaws.com, pods.eks.amazonaws.com]` | Audiences accepted for one injected identity token. Adding an audience expands the admitted workload-identity boundary. Non-EKS audiences use the IRSA-shaped contract; `pods.eks.amazonaws.com` is reserved for the exact EKS Pod Identity shape. |
| `wiremock.resourcePolicy.requestFloor.cpu` | `"100m"` | Minimum effective WireMock CPU request. |
| `wiremock.resourcePolicy.requestFloor.memory` | `128Mi` | Minimum effective WireMock memory request. |
| `wiremock.resourcePolicy.limitCeiling.cpu` | `"4"` | Maximum effective WireMock CPU limit. |
| `wiremock.resourcePolicy.limitCeiling.memory` | `4Gi` | Maximum effective WireMock memory limit. |
| `wiremock.config.default.options` | `[]` | Default WireMock CLI options for all mocks. |
| `wiremock.config.default.resources.requests.cpu` | `"0.5"` | Default WireMock CPU request. |
| `wiremock.config.default.resources.requests.memory` | `512Mi` | Default WireMock memory request. |
| `wiremock.config.default.resources.limits.cpu` | `"1"` | Default WireMock CPU limit. |
| `wiremock.config.default.resources.limits.memory` | `1Gi` | Default WireMock memory limit. |
| `wiremock.config.mocks` | `[]` | Per-mock WireMock config overrides. |

Set `wiremock.serviceAccount.create=false` with a name to use an existing dedicated service account. When admission is enabled, the resolved WireMock and API service-account names must differ, and WireMock must resolve to a nonempty name. The chart rejects a shared identity instead of falling back to the namespace default service account.

### WireMock Version Updater

| Value | Default | Description |
| --- | --- | --- |
| `wiremock.versionUpdater.enabled` | `false` | Deploy the catalog updater CronJob, ServiceAccount, and least-privilege RBAC. |
| `wiremock.versionUpdater.schedule` | `"0 2 * * *"` | Cron schedule. Jobs use `Forbid` concurrency and make one attempt with no same-Job retry. |
| `wiremock.versionUpdater.timeZone` | `Etc/UTC` | Kubernetes CronJob time zone. |
| `wiremock.versionUpdater.defaultVersionConstraint` | `"3.x"` | Default-advance constraint. Accepts only `3.x` or an exact minor line such as `3.13.x`. |
| `wiremock.versionUpdater.minorLines` | `5` | Number of latest stable 3.x minor lines kept selectable, from 1 through 50. |
| `wiremock.versionUpdater.registry.url` | `https://registry-1.docker.io` | Registry V2 HTTP(S) origin. |
| `wiremock.versionUpdater.registry.repository` | `wiremock/wiremock` | Registry repository used for tag discovery and catalog image names. |
| `wiremock.versionUpdater.registry.credentialsSecretName` | `""` | Existing Secret whose `username` and `password` keys provide optional registry credentials. |
| `wiremock.versionUpdater.image.repository` | `ghcr.io/letsrokk/mock-fleet/wiremock-updater` | Updater image repository. |
| `wiremock.versionUpdater.image.tag` | `""` | Updater image tag. Defaults to the chart `appVersion` when empty. |
| `wiremock.versionUpdater.image.pullPolicy` | `IfNotPresent` | Updater image pull policy. |
| `wiremock.versionUpdater.serviceAccount.create` | `true` | Create a dedicated updater ServiceAccount. |
| `wiremock.versionUpdater.serviceAccount.name` | `""` | Updater ServiceAccount name. A generated name is used when creation is enabled and this is empty. |
| `wiremock.versionUpdater.serviceAccount.annotations` | `{}` | Annotations for the updater ServiceAccount. |
| `wiremock.versionUpdater.resources.requests.cpu` | `"0.05"` | Updater CPU request. |
| `wiremock.versionUpdater.resources.requests.memory` | `128Mi` | Updater memory request. |
| `wiremock.versionUpdater.resources.limits.cpu` | `"0.5"` | Updater CPU limit. |
| `wiremock.versionUpdater.resources.limits.memory` | `512Mi` | Updater memory limit. |

The updater implements the Registry V2 tag-list API, including pagination, optional HTTP Basic credentials, and Bearer-token challenges. If credentials are configured, the Secret must contain both exact keys. An HTTPS registry accepts only HTTPS Bearer realms, including legitimate cross-origin services such as `auth.docker.io`. An HTTP registry accepts only a same-origin HTTP realm and is intended for a controlled local test registry. Realm userinfo is always rejected, and configured credentials are never sent cross-origin over HTTP.

Each run reads the named baseline, user, and catalog ConfigMaps exactly once. It ignores unstable tags and selects the newest image revision for the latest patch in each of the newest `minorLines` stable 3.x lines. The default advances only to a newer candidate that matches `defaultVersionConstraint`; it never downgrades. If the constrained default is outside that latest-minor window, it is also kept selectable. Versions no longer selectable remain as `retained.*` only while an exact baseline or user pin references them. The updater does not mutate configuration and has no Pod permissions, so it cannot restart active mocks or resolve desired/runtime drift.

Reconciliation validates the complete registry result, both configuration documents, every referenced version, and every current catalog entry before it performs one catalog update. The catalog may contain only `defaultVersion`, `selectable.<exact-version>`, and `retained.<exact-version>` keys; every image must be exact and match its key, a version cannot occur in both sections, and the default must be selectable. A malformed response, document, or catalog entry, missing reference, invalid constraint, or other precondition failure leaves the catalog unchanged. The update carries the ConfigMap's observed `resourceVersion`; a concurrent write produces a Kubernetes conflict and the Job fails without overwriting the newer catalog. `backoffLimit: 0` gives each scheduled Job one attempt and no same-Job retry. The next scheduled Job reconciles from a fresh snapshot.

### Dashboard

| Value | Default | Description |
| --- | --- | --- |
| `fleet.dash.enabled` | `true` | Deploy the dashboard service and deployment. |
| `fleet.dash.image.repository` | `ghcr.io/letsrokk/mock-fleet/dash` | Dashboard image repository. |
| `fleet.dash.image.tag` | `""` | Dashboard image tag. Defaults to the chart `appVersion` when empty. |
| `fleet.dash.image.pullPolicy` | `IfNotPresent` | Dashboard image pull policy. |
| `fleet.dash.replicas` | `1` | Dashboard replica count. |
| `fleet.dash.service.type` | `ClusterIP` | Dashboard service type. |
| `fleet.dash.service.ports.http` | `80` | Dashboard service HTTP port. |
| `fleet.dash.service.ports.targetHttp` | `8080` | Dashboard container HTTP port. |
| `fleet.dash.probes.liveness.path` | `/__fleet/dash/health/live` | Dashboard liveness probe path. |
| `fleet.dash.probes.liveness.initialDelaySeconds` | `5` | Dashboard liveness probe initial delay. |
| `fleet.dash.probes.liveness.periodSeconds` | `20` | Dashboard liveness probe period. |
| `fleet.dash.probes.liveness.timeoutSeconds` | `5` | Dashboard liveness probe timeout. |
| `fleet.dash.probes.liveness.successThreshold` | `1` | Dashboard liveness probe success threshold. |
| `fleet.dash.probes.liveness.failureThreshold` | `3` | Dashboard liveness probe failure threshold. |
| `fleet.dash.probes.readiness.path` | `/__fleet/dash/health/ready` | Dashboard readiness probe path. |
| `fleet.dash.probes.readiness.initialDelaySeconds` | `2` | Dashboard readiness probe initial delay. |
| `fleet.dash.probes.readiness.periodSeconds` | `10` | Dashboard readiness probe period. |
| `fleet.dash.probes.readiness.timeoutSeconds` | `5` | Dashboard readiness probe timeout. |
| `fleet.dash.probes.readiness.successThreshold` | `1` | Dashboard readiness probe success threshold. |
| `fleet.dash.probes.readiness.failureThreshold` | `3` | Dashboard readiness probe failure threshold. |
| `fleet.dash.probes.startup.path` | `/__fleet/dash/health/started` | Dashboard startup probe path. |
| `fleet.dash.probes.startup.initialDelaySeconds` | `2` | Dashboard startup probe initial delay. |
| `fleet.dash.probes.startup.periodSeconds` | `10` | Dashboard startup probe period. |
| `fleet.dash.probes.startup.timeoutSeconds` | `10` | Dashboard startup probe timeout. |
| `fleet.dash.probes.startup.successThreshold` | `1` | Dashboard startup probe success threshold. |
| `fleet.dash.probes.startup.failureThreshold` | `3` | Dashboard startup probe failure threshold. |
| `fleet.dash.resources.requests.cpu` | `"0.05"` | Dashboard CPU request. |
| `fleet.dash.resources.requests.memory` | `64Mi` | Dashboard memory request. |
| `fleet.dash.resources.limits.cpu` | `"0.25"` | Dashboard CPU limit. |
| `fleet.dash.resources.limits.memory` | `128Mi` | Dashboard memory limit. |

### Storage

| Value | Default | Description |
| --- | --- | --- |
| `storage.persistent` | `false` | Enable persistent WireMock mappings storage. |
| `storage.type` | `s3` | Persistent storage type. Only `s3` is supported. |
| `storage.mappingsPath` | `/workspace/mappings` | Path where `fleet-api` mounts persisted mappings. |
| `storage.annotations` | `{}` | Annotations added to the mappings PV and PVC. |
| `storage.s3.provisioner` | `s3.csi.aws.com` | S3 CSI driver name. |
| `storage.s3.storageClassName` | `""` | Storage class name used by the static PV and PVC. |
| `storage.s3.bucket` | `""` | S3 bucket name. Required when `storage.persistent=true`. |
| `storage.s3.path` | `/mock-fleet` | Path mounted inside WireMock init containers before per-mock subpaths are created. |
| `storage.s3.authenticationSource` | `driver` | S3 CSI authentication source. |
| `storage.s3.cacheSize` | `1Gi` | S3 CSI cache emptyDir size limit. |
| `storage.s3.mountOptions` | `[allow-delete, allow-overwrite, metadata-ttl minimal]` | S3 CSI mount options for shared API/WireMock mutations and reduced cross-mount staleness. They do not coordinate concurrent writes to one key. |

### Ingress

| Value | Default | Description |
| --- | --- | --- |
| `ingress.enabled` | `false` | Create an ingress resource. |
| `ingress.host` | `mock-fleet.localhost` | Public fleet host. |
| `ingress.className` | `""` | Ingress class name. |
| `ingress.annotations` | `{}` | Ingress annotations. |
| `ingress.tls` | `[]` | Ingress TLS entries. |
| `ingress.path` | `/` | Catch-all proxy ingress path. |
| `ingress.pathType` | `Prefix` | Ingress path type. |

### Runtime, RBAC, And Hazelcast

| Value | Default | Description |
| --- | --- | --- |
| `securityContext.runAsNonRoot` | `true` | Set `runAsNonRoot` on app pods. |
| `env.javaOpts` | `""` | Java options passed to proxy, API, and MCP pods. |
| `env.javaToolOptions` | unset | Optional non-dev `JAVA_TOOL_OPTIONS` for proxy, API, and MCP pods. |
| `env.userDir` | `/workspace` | Value for `user.dir` in proxy, API, and MCP pods. |
| `serviceAccount.create` | `true` | Create a service account for `fleet-api`. |
| `serviceAccount.name` | `""` | Existing service account name, or generated name when empty. |
| `serviceAccount.annotations` | `{}` | Service account annotations. |
| `rbac.create` | `true` | Create role and role binding for mock pod management. |
| `resourceQuota.enabled` | `true` | Create the namespace quota backstop. Disabling it removes the cluster-side aggregate pod/CPU/memory boundary. |
| `resourceQuota.hard.pods` | `30` | Namespace pod quota; size for active mocks, fixed workloads, and rollout or operator pods. |
| `resourceQuota.hard.requests.cpu` | `"8"` | Aggregate namespace CPU-request quota. |
| `resourceQuota.hard.requests.memory` | `12Gi` | Aggregate namespace memory-request quota. |
| `resourceQuota.hard.limits.cpu` | `"16"` | Aggregate namespace CPU-limit quota. |
| `resourceQuota.hard.limits.memory` | `24Gi` | Aggregate namespace memory-limit quota. |
| `hazelcast.clusterName` | `mock-fleet` | Cluster name used by embedded Hazelcast members. |
| `hazelcast.port` | `5701` | Embedded Hazelcast member and headless-service port. |
| `hazelcast.backupCount` | `1` | Synchronous backup count for distributed mock state. |
| `hazelcast.gracefulShutdownMaxWaitSeconds` | `30` | Maximum wait for graceful member shutdown. |

The chart creates `<fullname>-wiremock-user-config` for a new release and marks it `helm.sh/resource-policy: keep`. The API persists UI changes with named `get`, `watch`, `update`, and `patch` operations; it does not create, list, or delete ConfigMaps. A connected upgrade retains a pre-existing object instead of adopting it. For ArgoCD or another offline render/apply workflow, configure reconciliation so it does not overwrite UI-saved data. If `rbac.create=false`, grant the API service account those four named operations on this ConfigMap and pod `get`, `list`, `create`, and `delete`. When the updater is enabled, grant its service account named `get` on `<fullname>-wiremock-config` and `<fullname>-wiremock-user-config`, plus named `get`, `update`, and `patch` on `<fullname>-wiremock-version-catalog`. Do not restore namespace-wide ConfigMap or Deployment authority.

## Local Minikube Values

`values.minikube.yaml` enables a Traefik ingress and MCP at `mock-fleet.minikube.localhost`, attaches the ingress to the `websecure` entrypoint with router TLS enabled, sets `fleet.proxy.routing.mode=PATH`, and configures local persistent S3 storage values. Run `minikube tunnel` while using the deployment and trust the Minikube local CA in clients. The dashboard is available at `https://mock-fleet.minikube.localhost/__fleet/`; MCP uses `https://mock-fleet.minikube.localhost/__fleet/mcp`; the default WireMock mock is available at `https://mock-fleet.minikube.localhost/wiremock`. `make local-deploy` applies restricted PSA labels but does not inspect or alter Minikube's CNI; run the denial probe above before relying on NetworkPolicy.

```bash
helm upgrade --install mock-fleet deploy/helm/mock-fleet \
  --namespace mock-fleet \
  --create-namespace \
  -f deploy/helm/mock-fleet/values.minikube.yaml
```

The repository Makefile builds local images directly in the Minikube Docker daemon. Force a specific image rebuild after pulling clean source changes with `make local-deploy REBUILD=mcp`, replacing `mcp` with `dash`, `api`, or `proxy` as needed. Use `REBUILD=all` to rebuild every local image.
